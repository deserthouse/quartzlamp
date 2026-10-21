package io.github.deserthouse.quartzlamp.ui

import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.DeliveryReport
import io.github.deserthouse.quartzlamp.ble.LampConnection
import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import io.github.deserthouse.quartzlamp.ble.SessionReadyEvent
import io.github.deserthouse.quartzlamp.ble.SendResult
import io.github.deserthouse.quartzlamp.ble.SessionState
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** VM 单测桩：无延时、可编程查询读回（隔离 VM 槽位逻辑与假灯时序） */
class TestLampConnection : LampConnection {

    override val state = MutableStateFlow(ConnState.DISCONNECTED)
    override val scanned = MutableStateFlow<List<ScannedLamp>>(emptyList())
    override val scanning = MutableStateFlow(false)
    override val frameLog: SharedFlow<String> = LampConnection.sharedFrameLog
    override val frameHistory = MutableStateFlow<List<String>>(emptyList())
    override val sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())

    /** 会话就绪事件（AV04-08）：connect() 同步发出（构造期同步路径的可测面） */
    private val _sessionReady = MutableSharedFlow<SessionReadyEvent>(extraBufferCapacity = 16)
    override val sessionReady: SharedFlow<SessionReadyEvent> = _sessionReady

    val sentFrames = mutableListOf<String>()
    /** queryTimer(open) → 返回字节（null=读失败） */
    var timerRead: (Boolean) -> ByteArray? = { null }

    override fun startScan() {}
    override fun stopScan() {}
    override fun connect(lamp: ScannedLamp) {
        sessions.value = sessions.value + (lamp.mac to SessionState.CONNECTED)
        _sessionReady.tryEmit(SessionReadyEvent(lamp.mac, 1))
    }

    override fun disconnect(mac: String) {
        sessions.value = sessions.value + (mac to SessionState.DISCONNECTED)
    }

    override suspend fun sendTo(mac: String, frame: ByteArray): Boolean {
        sentFrames += LampFrames.hex(frame)
        return sessions.value[mac] == SessionState.CONNECTED
    }

    /** S1 测试钩子：该 MAC 投递失败（部分成功反例） */
    var failMac: String? = null

    override suspend fun sendDetailed(frame: ByteArray): DeliveryReport {
        val targets = sessions.value.filterValues { it == SessionState.CONNECTED }.keys.toSet()
        val hex = LampFrames.hex(frame)
        sentFrames += hex
        return DeliveryReport(targets, targets.associateWith { it != failMac })
    }

    override suspend fun send(frame: ByteArray): SendResult = sendDetailed(frame).toSendResult()

    override suspend fun queryTimer(open: Boolean): ByteArray? {
        sentFrames += LampFrames.hex(LampFrames.timerQuery(open))
        return timerRead(open)
    }
}
