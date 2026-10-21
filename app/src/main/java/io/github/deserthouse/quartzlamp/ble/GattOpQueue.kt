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
 * 队列终结语义（R44-01/Q1）：等待者收到的失败一律是 [QueueClosedException]
 * （IllegalStateException 族，可恢复）——不是裸 RuntimeException 沿协程上抛，
 * 更不是永不返回。等待方（write/notify/query/postReactiveAwait）捕获后折为
 * false/null 显式结果；等待方自身的取消仍以 CancellationException 原样传播。
 */
class QueueClosedException(message: String = "queue closed") : IllegalStateException(message)

/**
 * 每设备 GATT 操作串行队列（R19-04）：notify 初始化、时间同步、普通控制写、定时查询事务
 * 统一经此队列——**一次至多一个在途操作**，回调/失败/超时后才推进。
 * FastBle 2.3.4 无全局操作队列（BleBluetooth 按特征覆盖写回调），此前"send 直写+查询 Mutex"
 * 会被普通写穿插——复审探针已证。
 *
 * Q1 终态所有权（R44-01）：
 * - 每个可等待操作在**入队时注册、终结（完成/失败/取消）后 finally 注销**——
 *   注册表同时覆盖 queued 与 in-flight 两态，close/消费取消任一先发生都会结算全部等待者；
 * - close 幂等：拒收新操作 + 结算全部未完成等待者；消费 Job 取消同样结算（顺序无关，不悬挂）；
 * - 传输失败/超时折为 Boolean/null 显式结果（[execStep] 内部消化，不向等待方抛裸异常）；
 * - 律动（[postReactive]）conflate：未开始执行的待发律动只保留最新帧，共享等待者一并结算；
 * - 定时查询 = 单个组合操作（写→等本帧回调→读），天然不被穿插；
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
    private class WriteOp(val frame: ByteArray, val result: CompletableDeferred<Boolean>) : Op

    /** 律动帧容器：conflate 替换只改 frame，全部等待者共享最新一次执行的结果 */
    private class ReactiveHolder(var frame: ByteArray, val waiters: MutableList<CompletableDeferred<Boolean>> = ArrayList())

    private class ReactiveOp(val holder: ReactiveHolder) : Op
    private class QueryOp(val frame: ByteArray, val result: CompletableDeferred<ByteArray?>) : Op

    private val channel = Channel<Op>(Channel.UNLIMITED)

    /** 尚未开始执行的律动帧容器——conflate 替换点 */
    @Volatile
    private var pendingReactive: ReactiveHolder? = null

    /** Q1：未终结等待者注册表（queued + in-flight 同覆盖），终结后 finally 注销 */
    private val pending = java.util.Collections.synchronizedCollection(ArrayList<CompletableDeferred<*>>())

    @Volatile
    private var closed = false

    /** 会话关闭（R20-06 扩展 Q1）：拒收新操作、结算全部未终结等待者；幂等 */
    fun close() {
        if (closed) return
        closed = true
        channel.close()
        settlePending(QueueClosedException())
    }

    /** 启动消费循环；返回 Job 供断开时取消（取消与 close 顺序无关，等待者均有限时间终结） */
    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            for (op in channel) {
                try {
                    when (op) {
                        is NotifyOp -> {
                            try {
                                val ok = execStep("notify") { c ->
                                    transport.enableNotify { ok, err ->
                                        if (ok) c.resume(Unit) else c.resumeWithException(RuntimeException("notify fail: $err"))
                                    }
                                }
                                op.result.complete(ok)
                            } finally {
                                if (!op.result.isCompleted) op.result.completeExceptionally(QueueClosedException("op aborted"))
                                pending.remove(op.result)
                            }
                        }
                        is WriteOp -> {
                            try {
                                // 注意：不能写 op.result.complete(execStep(...))——安全调用在 result==null
                                // 时连参数都不求值，post 投递的帧会被整体跳过
                                val ok = execStep("write") { c ->
                                    transport.write(op.frame) { ok, err ->
                                        if (ok) c.resume(Unit) else c.resumeWithException(RuntimeException("write fail: $err"))
                                    }
                                }
                                op.result.complete(ok)
                            } finally {
                                if (!op.result.isCompleted) op.result.completeExceptionally(QueueClosedException("op aborted"))
                                pending.remove(op.result)
                            }
                        }
                        is ReactiveOp -> {
                            try {
                                if (pendingReactive === op.holder) pendingReactive = null
                                val ok = execStep("reactive") { c ->
                                    transport.write(op.holder.frame) { ok, err ->
                                        if (ok) c.resume(Unit) else c.resumeWithException(RuntimeException("reactive fail: $err"))
                                    }
                                }
                                op.holder.waiters.forEach { it.complete(ok) } // 共享等待者一并结算（Q1.4）
                            } finally {
                                op.holder.waiters.forEach { w ->
                                    if (!w.isCompleted) w.completeExceptionally(QueueClosedException("op aborted"))
                                    pending.remove(w)
                                }
                                op.holder.waiters.clear()
                            }
                        }
                        is QueryOp -> {
                            try {
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
                            } finally {
                                if (!op.result.isCompleted) op.result.completeExceptionally(QueueClosedException("op aborted"))
                                pending.remove(op.result)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    settlePending(QueueClosedException("consumer cancelled"))
                    throw e
                }
            }
        } catch (e: Exception) {
            // channel 已 close：等待者已在 close() 中统一结算
        }
    }

    /** 结算全部未终结等待者（close/消费取消共用）；已终结者 complete 返回 false 无副作用 */
    private fun settlePending(reason: QueueClosedException) {
        val snapshot = synchronized(pending) { pending.toList() }
        snapshot.forEach { it.completeExceptionally(reason) }
        synchronized(pending) { pending.clear() }
    }

    private fun dispatch(op: Op, result: CompletableDeferred<*>): Boolean {
        if (closed) {
            result.completeExceptionally(QueueClosedException())
            return false
        }
        pending.add(result)
        if (channel.trySend(op).isSuccess) return true
        pending.remove(result)
        result.completeExceptionally(QueueClosedException())
        return false
    }

    /** 挂起等待 notify 建立；true=成功（关闭/取消/失败为 false，不抛非取消异常） */
    suspend fun notify(): Boolean {
        val d = CompletableDeferred<Boolean>()
        if (!dispatch(NotifyOp(d), d)) return false
        return awaitBoolean(d)
    }

    /** 挂起写（等本帧回调）；true=成功 */
    suspend fun write(frame: ByteArray): Boolean {
        val d = CompletableDeferred<Boolean>()
        if (!dispatch(WriteOp(frame, d), d)) return false
        return awaitBoolean(d)
    }

    /** 非阻塞投递（广播控制帧；结果只入 [lastError] 日志） */
    fun post(frame: ByteArray) {
        if (closed) return
        val d = CompletableDeferred<Boolean>()
        if (dispatch(WriteOp(frame, d), d)) {
            // 有终结追踪但无外部等待者：消费端完成 d（失败经 fail() 入 lastError）
            d.invokeOnCompletion { _ -> pending.remove(d) }
        }
    }

    /** 律动帧：未开始执行的待发律动只保留最新（conflate） */
    fun postReactive(frame: ByteArray) {
        if (closed) return
        val pendingR = pendingReactive
        if (pendingR != null) {
            pendingR.frame = frame
        } else {
            val holder = ReactiveHolder(frame)
            pendingReactive = holder
            channel.trySend(ReactiveOp(holder))
        }
    }

    /** 挂起版律动投递（T44-01 修复：等待者入 holder 共享结算，成功 ACK 有限时间返回真实结果） */
    suspend fun postReactiveAwait(frame: ByteArray): Boolean {
        if (closed) return false
        val d = CompletableDeferred<Boolean>()
        val holder = pendingReactive
        if (holder != null) {
            // 已有排队未执行的律动：并入共享等待者，conflate 到最新帧一并结算
            holder.frame = frame
            holder.waiters.add(d)
            pending.add(d)
        } else {
            val fresh = ReactiveHolder(frame)
            fresh.waiters.add(d)
            pendingReactive = fresh
            if (!dispatch(ReactiveOp(fresh), d)) return false
        }
        return awaitBoolean(d)
    }

    /** 定时查询事务（写→读，原子不被穿插）；失败/超时/关闭返回 null */
    suspend fun query(frame: ByteArray): ByteArray? {
        val d = CompletableDeferred<ByteArray?>()
        if (!dispatch(QueryOp(frame, d), d)) return null
        return awaitNullable(d)
    }

    // ━━━ 内部 ━━━

    /** 等待者统一出口：关闭折为显式失败结果；等待方自身取消原样传播 */
    private suspend fun awaitBoolean(d: CompletableDeferred<Boolean>): Boolean =
        try {
            d.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: QueueClosedException) {
            false
        }

    private suspend fun <T> awaitNullable(d: CompletableDeferred<T>): T? =
        try {
            d.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: QueueClosedException) {
            null
        }

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
}
