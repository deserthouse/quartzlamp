package io.github.deserthouse.quartzlamp.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 扫描到的灯设备 */
data class ScannedLamp(
    val name: String,
    val mac: String,
    val rssi: Int,
)

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/**
 * BLE 门面 —— UI 层唯一入口（FastBle 类永不进入 UI 层）。
 * 实现两个：FastBleLampConnection（真机）/ FakeLampConnection（AVD 自测）。
 * 日志契约：send() 打 BLE-WRITE 同格式日志（与原厂 App logcat 对照，验收定位用）。
 */
interface LampConnection {
    val state: StateFlow<ConnState>
    val scanned: StateFlow<List<ScannedLamp>>
    /** BLE-WRITE 格式帧日志（hex 串），验收诊断用 */
    val frameLog: SharedFlow<String>

    fun startScan()
    fun stopScan()
    fun connect(lamp: ScannedLamp)
    fun disconnect()

    /** 下发帧（遍历所有已连接设备，多灯广播——对齐厂商行为） */
    fun send(frame: ByteArray)

    /** 定时查询：发送查询帧后走 GATT read，异步回调原始读回字节 */
    fun queryTimer(open: Boolean, onResult: (ByteArray?) -> Unit)

    companion object {
        /** 全局帧日志汇聚（两个实现共用），App 内诊断页可订阅 */
        val sharedFrameLog: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 64)
    }
}
