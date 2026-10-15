package io.github.deserthouse.quartzlamp.ble

import android.util.Log
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

/**
 * 假灯——AVD/无灯环境的全流程自测实现（仅存在于 debug 变体，release 物理隔离，F11）。
 *
 * 行为契约与真实实现同构（F11 关闭条件：模拟不再跳过将被宣称验证的逻辑）：
 * - 两台假灯可同时连接，sessions 按 MAC 维护，disconnect(mac) 只断一台；
 * - 定时查询=真事务语义：send 的 timerSet 帧被记录，queryTimer 读回**所写槽位**（写读闭环）；
 * - 失败注入：failNextQueries>0 时查询返回 null；unexpectedDisconnect(mac) 模拟意外断连
 *   （触发有限重连，参数与真实实现对齐且可注入缩短）；
 * - 扫描/连接 Job 可取消（stopScan 后不再回填设备）。
 */
class FakeLampConnection : LampConnection {

    override val isDemo = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    override val state: StateFlow<ConnState> = _state
    private val _scanned = MutableStateFlow<List<ScannedLamp>>(emptyList())
    override val scanned: StateFlow<List<ScannedLamp>> = _scanned
    override val frameLog: SharedFlow<String> = LampConnection.sharedFrameLog
    private val _frameHistory = MutableStateFlow<List<String>>(emptyList())
    override val frameHistory: StateFlow<List<String>> = _frameHistory
    private val history = ArrayDeque<String>()
    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessions: StateFlow<Map<String, SessionState>> = _sessions

    /** 两台假灯：多灯并存/断 A 不影响 B 的可测性 */
    val fakeLamps = listOf(
        ScannedLamp("FakeLamp-2210", "00:11:22:33:44:55", -52),
        ScannedLamp("FakeLamp-1102", "AA:BB:CC:DD:EE:FF", -61)
    )

    // ━━━ 失败注入钩子（仅 debug）━━━
    /** >0 时接下来 N 次定时查询返回 null（写失败路径） */
    var failNextQueries = 0
    /** 连接建立延时（测试可缩短） */
    var connectDelayMs = 1_000L
    /** 意外断连重连间隔（测试可缩短） */
    var reconnectDelayMs = RECONNECT_INTERVAL_MS
    /** 意外断连后重连是否成功（false=耗尽间隔落 DISCONNECTED） */
    var reconnectSucceeds = true

    private val scanJobs = mutableListOf<Job>()
    private val connectJobs = mutableListOf<Job>()

    /** 各槽位灯端配置：open→(hour,minute,weekBits)；0xFF=未设置 */
    private val lampTimers = mutableMapOf<Boolean, Triple<Int, Int, Int>>()

    override fun startScan() {
        if (_state.value == ConnState.SCANNING) return
        _scanned.value = emptyList()
        _state.value = ConnState.SCANNING
        scanJobs += scope.launch {
            delay(2000)
            _scanned.value = fakeLamps
            delay(6000)
            if (_state.value == ConnState.SCANNING) _state.value = aggregated()
        }
    }

    override fun stopScan() {
        scanJobs.forEach { it.cancel() }
        scanJobs.clear()
        if (_state.value == ConnState.SCANNING) _state.value = aggregated()
    }

    override fun connect(lamp: ScannedLamp) {
        if (fakeLamps.none { it.mac == lamp.mac }) return
        if (_sessions.value[lamp.mac] == SessionState.CONNECTED ||
            _sessions.value[lamp.mac] == SessionState.CONNECTING
        ) return
        _sessions.update { it + (lamp.mac to SessionState.CONNECTING) }
        connectJobs += scope.launch {
            delay(connectDelayMs)
            _sessions.update { it + (lamp.mac to SessionState.CONNECTED) }
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
        connectJobs.forEach { it.cancel() }
        connectJobs.clear()
        _sessions.update { it + (mac to SessionState.DISCONNECTED) }
        refresh()
    }

    /** 测试钩子：模拟意外断连（灯断电）→ 进入有限重连，delay 后按 reconnectSucceeds 落位 */
    fun unexpectedDisconnect(mac: String) {
        if (_sessions.value[mac] != SessionState.CONNECTED) return
        _sessions.update { it + (mac to SessionState.RECONNECTING) }
        refresh()
        reconnectJobs[mac] = scope.launch {
            if (reconnectSucceeds) {
                delay(reconnectDelayMs)
            } else {
                repeat(RECONNECT_ATTEMPTS) { delay(reconnectDelayMs) }
            }
            // 期间被主动断开/重连则以最新状态为准
            if (_sessions.value[mac] != SessionState.RECONNECTING) return@launch
            _sessions.update {
                it + (
                    mac to if (reconnectSucceeds) SessionState.CONNECTED else SessionState.DISCONNECTED
                    )
            }
            refresh()
        }
    }

    private val reconnectJobs = mutableMapOf<String, Job>()

    override fun send(frame: ByteArray) {
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        recordFrame(hex)
        // 假灯端存储：timerSet 帧写入槽位，queryTimer 才能读回（F11 写读闭环）
        if (frame.size == 9 && frame[0] == 0x7E.toByte() && frame[2] == 0x82.toByte()) {
            val hour = frame[3].toInt()
            if (hour != -1) {
                lampTimers[frame[6].toInt() == 0] =
                    Triple(hour, frame[4].toInt(), frame[7].toInt())
            } else {
                lampTimers.remove(frame[6].toInt() == 0)
            }
        }
    }

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
        _state.value == ConnState.SCANNING -> ConnState.SCANNING
        _sessions.value.containsValue(SessionState.CONNECTING) ||
            _sessions.value.containsValue(SessionState.RECONNECTING) -> ConnState.CONNECTING
        else -> ConnState.DISCONNECTED
    }

    private fun refresh() {
        if (_state.value != ConnState.SCANNING) _state.value = aggregated()
    }

    companion object {
        const val RECONNECT_ATTEMPTS = 3
        const val RECONNECT_INTERVAL_MS = 4_000L
    }
}
