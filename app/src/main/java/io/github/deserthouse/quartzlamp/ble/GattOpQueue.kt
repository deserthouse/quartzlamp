package io.github.deserthouse.quartzlamp.ble

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 每设备 GATT 操作串行队列（R19-04）：notify 初始化、时间同步、普通控制写、定时查询事务
 * 统一经此队列——**一次至多一个在途操作**，回调/失败/超时后才推进。
 * FastBle 2.3.4 无全局操作队列（BleBluetooth 按特征覆盖写回调），此前"send 直写+查询 Mutex"
 * 会被普通写穿插——复审探针已证。
 *
 * - 律动写（[postReactive]）conflate：未开始执行的待发律动帧只保留最新值；
 * - 定时查询 = 单个组合操作（写→等本帧回调→读），天然不被穿插；
 * - 可等待操作（[notify]/[write]/[query]）经 CompletableDeferred 回结果，超时/失败不阻塞队列；
 * - 传输抽象 [GattTransport]，JVM 单测可注入（探针反例的正式回归载体）；
 * - 错误记入 [lastError]（tag=设备 MAC，含阶段），不中断队列。
 */
interface GattTransport {
    fun enableNotify(done: (ok: Boolean, error: String?) -> Unit)
    fun write(frame: ByteArray, done: (ok: Boolean, error: String?) -> Unit)
    fun read(done: (data: ByteArray?, error: String?) -> Unit)
}

class GattOpQueue(
    private val tag: String,
    private val transport: GattTransport,
    private val opTimeoutMs: Long = 8_000L,
) {
    /** 最近一次操作错误（"阶段: 描述"），诊断用 */
    val lastError = MutableStateFlow<String?>(null)

    private sealed interface Op
    private class NotifyOp(val result: CompletableDeferred<Boolean>) : Op
    private class WriteOp(val frame: ByteArray, val result: CompletableDeferred<Boolean>?) : Op
    private class ReactiveOp(val holder: ReactiveHolder, val result: CompletableDeferred<Boolean>? = null) : Op
    private class QueryOp(val frame: ByteArray, val result: CompletableDeferred<ByteArray?>) : Op

    private class ReactiveHolder(var frame: ByteArray)

    private val channel = Channel<Op>(Channel.UNLIMITED)

    /** 待执行（尚未开始）的律动帧容器——conflate 替换点 */
    @Volatile
    private var pendingReactive: ReactiveHolder? = null

    /** R20-06：在途/排队的结果等待者注册表——close 时统一失败，杜绝悬留 */
    private val pendingResults = java.util.Collections.synchronizedCollection(ArrayList<CompletableDeferred<*>>())

    @Volatile
    private var closed = false

    /** 会话关闭（R20-06）：拒收新操作、失败全部等待者；已发往平台的在途操作由 Job 取消兜底 */
    fun close() {
        closed = true
        channel.close()
        val snapshot = synchronized(pendingResults) { pendingResults.toList() }
        snapshot.forEach {
            it.completeExceptionally(RuntimeException("queue closed"))
        }
        synchronized(pendingResults) { pendingResults.clear() }
    }

    /** 启动消费循环；返回 Job 供断开时取消（在途挂起操作将随取消抛出） */
    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            for (op in channel) {
                try {
                    when (op) {
                        is NotifyOp -> {
                            pendingResults.remove(op.result)
                            op.result.complete(execStep("notify") { c ->
                                transport.enableNotify { ok, err ->
                                    if (ok) c.resume(Unit) else c.resumeWithException(RuntimeException("notify fail: $err"))
                                }
                            })
                        }
                        is WriteOp -> {
                            // 注意：不能写 op.result?.complete(execStep(...))——安全调用在 result==null
                            // 时连参数都不求值，post 投递的帧会被整体跳过
                            pendingResults.remove(op.result)
                            val ok = execStep("write") { c ->
                                transport.write(op.frame) { ok, err ->
                                    if (ok) c.resume(Unit) else c.resumeWithException(RuntimeException("write fail: $err"))
                                }
                            }
                            op.result?.complete(ok)
                        }
                        is ReactiveOp -> {
                            if (pendingReactive === op.holder) pendingReactive = null
                            val frame = op.holder.frame
                            execStep("reactive") { c ->
                                transport.write(frame) { ok, err ->
                                    if (ok) c.resume(Unit) else c.resumeWithException(RuntimeException("reactive fail: $err"))
                                }
                            }
                        }
                        is QueryOp -> {
                            var readData: ByteArray? = null
                            val wok = execStep("query-write") { c ->
                                transport.write(op.frame) { ok, err ->
                                    if (ok) c.resume(Unit) else c.resumeWithException(RuntimeException("query write fail: $err"))
                                }
                            }
                            val rok = wok && execStep("query-read") { c ->
                                transport.read { data, err ->
                                    if (data != null) {
                                        readData = data
                                        c.resume(Unit)
                                    } else c.resumeWithException(RuntimeException("query read fail: $err"))
                                }
                            }
                            op.result.complete(if (rok) readData else null)
                            continue
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    fail("${phaseOf(op)}: timeout（>${opTimeoutMs}ms，跳过该操作）")
                } catch (e: CancellationException) {
                    // 队列被取消（设备断开）：在途查询的等待方一并取消，退出循环
                    if (op is QueryOp) op.result.cancel(e)
                    throw e
                } catch (e: Exception) {
                    fail("${phaseOf(op)}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // channel 已 close：等待者已在 close() 中统一失败
        }
    }

    private fun <T> dispatch(op: Op, result: CompletableDeferred<T>): Boolean {
        if (closed) {
            result.cancel(CancellationException("queue closed"))
            return false
        }
        pendingResults.add(result)
        return channel.trySend(op).isSuccess || run {
            pendingResults.remove(result)
            result.cancel(CancellationException("queue closed"))
            false
        }
    }

    /** 挂起等待 notify 建立；true=成功（超时/失败为 false，不抛） */
    suspend fun notify(): Boolean {
        val d = CompletableDeferred<Boolean>()
        if (!dispatch(NotifyOp(d), d)) return false
        return d.await()
    }

    /** 挂起写（等本帧回调）；true=成功 */
    suspend fun write(frame: ByteArray): Boolean {
        val d = CompletableDeferred<Boolean>()
        if (!dispatch(WriteOp(frame, d), d)) return false
        return d.await()
    }

    /** 非阻塞投递（广播控制帧；结果只入 [lastError] 日志） */
    fun post(frame: ByteArray) {
        if (closed) return
        channel.trySend(WriteOp(frame, null))
    }

    /** 律动帧：未开始执行的待发律动只保留最新（conflate） */
    fun postReactive(frame: ByteArray) {
        if (closed) return
        val pending = pendingReactive
        if (pending != null) {
            pending.frame = frame
        } else {
            val holder = ReactiveHolder(frame)
            pendingReactive = holder
            channel.trySend(ReactiveOp(holder))
        }
    }

    /** 挂起版律动投递（R20-03：需真实结果时用；结果语义=已发往该设备队列并完成） */
    suspend fun postReactiveAwait(frame: ByteArray): Boolean {
        if (closed) return false
        val d = CompletableDeferred<Boolean>()
        val holder = ReactiveHolder(frame)
        pendingReactive = holder
        if (!dispatch(ReactiveOp(holder), d)) return false
        return d.await()
    }

    /** 定时查询事务（写→读，原子不被穿插）；失败/超时返回 null */
    suspend fun query(frame: ByteArray): ByteArray? {
        val d = CompletableDeferred<ByteArray?>()
        if (!dispatch(QueryOp(frame, d), d)) return null
        return d.await()
    }

    // ━━━ 内部 ━━━

    /** 单步执行：回调门 + 超时；结果折为 Boolean（不吞取消） */
    private suspend fun execStep(phase: String, call: (CancellableContinuation<Unit>) -> Unit): Boolean = try {
        withTimeout(opTimeoutMs) { suspendCancellableCoroutine<Unit> { c -> call(c) } }
        true
    } catch (e: TimeoutCancellationException) {
        fail("$phase: timeout（>${opTimeoutMs}ms）")
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail("$phase: ${e.message}")
        false
    }

    private fun fail(msg: String) {
        Log.w("BLE", "[$tag] $msg")
        lastError.value = "[$tag] $msg"
    }

    private fun phaseOf(op: Op): String = when (op) {
        is NotifyOp -> "notify"
        is WriteOp -> "write"
        is ReactiveOp -> "reactive"
        is QueryOp -> "query"
    }
}

