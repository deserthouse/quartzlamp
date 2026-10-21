package io.github.deserthouse.quartzlamp.ble

import android.util.Log
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * 假灯——AVD/无灯环境的全流程自测实现（仅存在于 debug 变体，release 物理隔离，F11）。
 *
 * 行为契约与真实实现同构（R19-03/07 复审修复后）：
 * - 扫描是独立事实：停止/超时/启动失败后聚合态归位、可再扫（真实现同语义）；
 * - 连接 Job 按 MAC 持有：断 A 不影响连接中的 B（R19-07 假灯侧反例修复）；
 * - 两台假灯可同时连接，sessions 按 MAC 维护；
 * - 定时查询=写读闭环（timerSet 帧被记录，queryTimer 读回所写槽位）；
 * - 失败注入：failNextQueries / unexpectedDisconnect（有限重连，参数可缩短）；
 * - 扫描/连接 Job 可取消（stopScan 后不再回填设备）。
 */
class FakeLampConnection : LampConnection {

    override val isDemo = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    override val state: StateFlow<ConnState> = _state

    /** 扫描独立事实（R19-03，与真实实现同语义） */
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning

    private val _scanned = MutableStateFlow<List<ScannedLamp>>(emptyList())
    override val scanned: StateFlow<List<ScannedLamp>> = _scanned
    override val frameLog: SharedFlow<String> = LampConnection.sharedFrameLog
    private val _frameHistory = MutableStateFlow<List<String>>(emptyList())
    override val frameHistory: StateFlow<List<String>> = _frameHistory
    private val history = ArrayDeque<String>()
    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessions: StateFlow<Map<String, SessionState>> = _sessions

    /** 会话就绪事件（AV04-08，与真实实现同语义：连接完成即发） */
    private val _sessionReady = MutableSharedFlow<SessionReadyEvent>(extraBufferCapacity = 16)
    override val sessionReady: SharedFlow<SessionReadyEvent> = _sessionReady

    /** 两台假灯：多灯并存/断 A 不影响 B 的可测性 */
    val fakeLamps = listOf(
        ScannedLamp("FakeLamp-2210", "00:11:22:33:44:55", -52),
        ScannedLamp("FakeLamp-1102", "AA:BB:CC:DD:EE:FF", -61)
    )

    // ━━━ 测试注入钩子（仅 debug）━━━
    /** >0 时接下来 N 次定时查询返回 null（写失败路径） */
    var failNextQueries = 0
    /** 连接建立延时 */
    var connectDelayMs = 1_000L
    /** 意外断连重连间隔 */
    var reconnectDelayMs = RECONNECT_INTERVAL_MS
    /** 意外断连后重连是否成功（false=耗尽间隔落 DISCONNECTED） */
    var reconnectSucceeds = true
    /** GAP-1：逐次重连结果序列（非空时按序消费，true=该次成功；耗尽后回落 reconnectSucceeds） */
    val reconnectFailSeq = mutableListOf<Boolean>()
    /** 扫描出灯延时 */
    var scanDelayMs = 2_000L
    /** 扫描窗口总长（到时自动结束） */
    var scanTimeoutMs = 8_000L

    private val scanJobs = mutableListOf<Job>()

    /** 连接 Job 按 MAC（R19-07：断 A 只取消 A） */
    private val connectJobsByMac = mutableMapOf<String, Job>()
    private val reconnectJobs = mutableMapOf<String, Job>()

    /** 各槽位灯端配置：open→(hour,minute,weekBits) */
    private val lampTimers = mutableMapOf<Boolean, Triple<Int, Int, Int>>()

    override fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        _scanned.value = emptyList()
        _state.value = ConnState.SCANNING
        scanJobs += scope.launch {
            delay(scanDelayMs)
            _scanned.value = fakeLamps
            delay((scanTimeoutMs - scanDelayMs).coerceAtLeast(0))
            _scanning.value = false
            _state.value = aggregated()
        }
    }

    override fun stopScan() {
        scanJobs.forEach { it.cancel() }
        scanJobs.clear()
        _scanning.value = false
        _state.value = aggregated()
    }

    override fun connect(lamp: ScannedLamp) {
        if (fakeLamps.none { it.mac == lamp.mac }) return
        if (_sessions.value[lamp.mac] == SessionState.CONNECTED ||
            _sessions.value[lamp.mac] == SessionState.CONNECTING
        ) return
        _sessions.update { it + (lamp.mac to SessionState.CONNECTING) }
        connectJobsByMac[lamp.mac] = scope.launch {
            delay(connectDelayMs)
            _sessions.update { it + (lamp.mac to SessionState.CONNECTED) }
            // Q2：携带会话身份（Fake 以每 MAC 连接序号近似 sessionId）
            val seq = sessionSeq.computeIfAbsent(lamp.mac) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
            _sessionReady.tryEmit(SessionReadyEvent(lamp.mac, seq))
            val c = java.util.Calendar.getInstance()
            send(
                LampFrames.timeSync(
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.DAY_OF_WEEK) - 1
                )
            )
            refresh()
        }
    }

    override fun disconnect(mac: String) {
        // 只取消本 MAC 的连接任务（R19-07：断 A 不拖死连接中的 B）
        connectJobsByMac.remove(mac)?.cancel()
        _sessions.update { it + (mac to SessionState.DISCONNECTED) }
        refresh()
    }

    /** 测试钩子：模拟意外断连（灯断电）→ 进入有限重连，delay 后按 reconnectSucceeds 落位 */
    fun unexpectedDisconnect(mac: String) {
        if (_sessions.value[mac] != SessionState.CONNECTED) return
        _sessions.update { it + (mac to SessionState.RECONNECTING) }
        refresh()
        reconnectJobs[mac] = scope.launch {
            // GAP-1：按序列逐次判定（空则回落 reconnectSucceeds 总开关）；
            // 终态=最后一次尝试的真实结果（R44-02 同族：不得用总开关覆盖逐次结果）
            var lastOk = false
            for (attemptIdx in 0 until RECONNECT_ATTEMPTS) {
                delay(reconnectDelayMs)
                lastOk = reconnectFailSeq.removeFirstOrNull() ?: reconnectSucceeds
                if (lastOk) break
            }
            // 期间被主动断开/重连则以最新状态为准
            if (_sessions.value[mac] != SessionState.RECONNECTING) return@launch
            _sessions.update {
                it + (
                    mac to if (lastOk) SessionState.CONNECTED else SessionState.DISCONNECTED
                    )
            }
            refresh()
        }
    }

    private val sessionSeq = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    override suspend fun sendTo(mac: String, frame: ByteArray): Boolean {
        if (_sessions.value[mac] != SessionState.CONNECTED) return false
        recordFrame(LampFrames.hex(frame))
        return true
    }

    override suspend fun sendDetailed(frame: ByteArray): DeliveryReport {
        val hex = LampFrames.hex(frame)
        // R1 解锁（codex 定位缺口）：与 send 同源打 BLE-WRITE——logcat 帧对照（BC06 一致性等）依赖此日志
        android.util.Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        _frameHistory.value = (listOf(hex) + _frameHistory.value).take(LampConnection.FRAME_HISTORY_LIMIT)
        // 假灯端存储：timerSet 帧被记录，queryTimer 才能读回（F11 写读闭环）
        if (frame.size == 9 && frame[0] == 0x7E.toByte() && frame[2] == 0x82.toByte()) {
            val hour = frame[3].toInt()
            if (hour != -1) {
                lampTimers[frame[6].toInt() == 0] =
                    Triple(hour, frame[4].toInt(), frame[7].toInt())
            } else {
                lampTimers.remove(frame[6].toInt() == 0)
            }
        }
        val targets = _sessions.value.filterValues { it == SessionState.CONNECTED }.keys.toSet()
        return DeliveryReport(targets, targets.associateWith { true })
    }

    override suspend fun send(frame: ByteArray): SendResult = sendDetailed(frame).toSendResult()

    override suspend fun queryTimer(open: Boolean): ByteArray? {
        val query = LampFrames.timerQuery(open)
        val hex = LampFrames.hex(query)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        recordFrame(hex)
        delay(300)
        if (failNextQueries > 0) {
            failNextQueries--
            return null
        }
        if (_sessions.value.values.none { it == SessionState.CONNECTED }) return null
        val slot = lampTimers[open]
        return if (slot == null) {
            byteArrayOf(0x7E, 0x08, 0x82.toByte(), -1, -1, -1, if (open) 0 else 1, 0, 0xEF.toByte())
        } else {
            byteArrayOf(
                0x7E, 0x08, 0x82.toByte(), slot.first.toByte(), slot.second.toByte(), 0,
                if (open) 0 else 1, slot.third.toByte(), 0xEF.toByte()
            )
        }
    }

    private fun recordFrame(hex: String) {
        synchronized(history) {
            history.addLast(hex)
            while (history.size > LampConnection.FRAME_HISTORY_LIMIT) history.removeFirst()
        }
        _frameHistory.value = synchronized(history) { history.toList() }
    }

    private fun aggregated(): ConnState = when {
        _sessions.value.containsValue(SessionState.CONNECTED) -> ConnState.CONNECTED
        _scanning.value -> ConnState.SCANNING
        _sessions.value.containsValue(SessionState.CONNECTING) ||
            _sessions.value.containsValue(SessionState.RECONNECTING) -> ConnState.CONNECTING
        else -> ConnState.DISCONNECTED
    }

    private fun refresh() {
        // 扫描事实独立于连接聚合：聚合函数自身先判已连接、再判扫描事实
        _state.value = aggregated()
    }

    companion object {
        const val RECONNECT_ATTEMPTS = 3
        const val RECONNECT_INTERVAL_MS = 4_000L
    }
}
