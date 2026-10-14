package io.github.deserthouse.quartzlamp.ble

import android.util.Log
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 假灯——AVD/无灯环境的全流程自测实现。
 * 行为模型：模拟扫描 2 秒出 1 台灯；连接 1 秒建立；定时查询返回可预设的读回帧。
 */
class FakeLampConnection : LampConnection {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    private val _scanned = MutableStateFlow<List<ScannedLamp>>(emptyList())
    override val state: StateFlow<ConnState> = _state
    override val scanned: StateFlow<List<ScannedLamp>> = _scanned
    override val frameLog: SharedFlow<String> = LampConnection.sharedFrameLog

    /** 测试钩子：queryTimer 返回的读回帧 */
    var fakeTimerReadOpen: ByteArray = byteArrayOf(
        0x7E, 0x08, 0x82.toByte(), 0x07, 0x1E, 0, 0, 0x15, 0xEF.toByte()
    )
    var fakeTimerReadClose: ByteArray = byteArrayOf(
        0x7E, 0x08, 0x82.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 0, 0xEF.toByte()
    )

    private val fakeLamp = ScannedLamp("FakeLamp-2210", "00:11:22:33:44:55", -52)

    override fun startScan() {
        if (_state.value != ConnState.DISCONNECTED) return
        _scanned.value = emptyList()
        _state.value = ConnState.SCANNING
        scope.launch {
            delay(2000)
            _scanned.value = listOf(fakeLamp)
            delay(6000)
            if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
        }
    }

    override fun stopScan() {
        // 只在扫描态复位；不得误杀已建立的连接（离开设备页会触发 onDispose）
        if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
    }

    override fun connect(lamp: ScannedLamp) {
        if (lamp.mac != fakeLamp.mac) return
        _state.value = ConnState.CONNECTING
        scope.launch {
            delay(1000)
            _state.value = ConnState.CONNECTED
        }
    }

    override fun disconnect() {
        _state.value = ConnState.DISCONNECTED
    }

    override fun send(frame: ByteArray) {
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
    }

    override fun queryTimer(open: Boolean, onResult: (ByteArray?) -> Unit) {
        send(LampFrames.timerQuery(open))
        scope.launch {
            delay(300)
            onResult(if (open) fakeTimerReadOpen else fakeTimerReadClose)
        }
    }
}
