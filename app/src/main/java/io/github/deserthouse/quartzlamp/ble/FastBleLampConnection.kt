package io.github.deserthouse.quartzlamp.ble

import android.util.Log
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleGattCallback
import com.clj.fastble.callback.BleReadCallback
import com.clj.fastble.callback.BleScanCallback
import com.clj.fastble.callback.BleWriteCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.BleException
import com.clj.fastble.scan.BleScanRuleConfig
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FastBle 2.3.4 厂商同款实现（初始化参数照抄 docs/ble_protocol.md §1）。
 * 变量隔离决策（2026-10-15）：传输层保持厂商已验证组合，故障定位时嫌疑最小。
 */
class FastBleLampConnection(private val app: android.app.Application) : LampConnection {

    private val bleManager = BleManager.getInstance()
    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    private val _scanned = MutableStateFlow<List<ScannedLamp>>(emptyList())
    override val state: StateFlow<ConnState> = _state
    override val scanned: StateFlow<List<ScannedLamp>> = _scanned
    override val frameLog: SharedFlow<String> = LampConnection.sharedFrameLog

    /** 厂商识别字节（MyApplication.<init> 实证） */
    private val colorByte1: Byte = 0x22
    private val colorByte2: Byte = 0x20

    private val seen = LinkedHashMap<String, ScannedLamp>()

    fun init() {
        bleManager
            .enableLog(true)
            .setReConnectCount(3, 4000L)
            .setMaxConnectCount(7)
            .setConnectOverTime(99000L)
            .setOperateTimeout(5000)
        bleManager.initScanRule(
            BleScanRuleConfig.Builder()
                .setAutoConnect(false)
                .setScanTimeOut(8000L)
                .build()
        )
    }

    override fun startScan() {
        if (_state.value != ConnState.DISCONNECTED) return
        seen.clear()
        _scanned.value = emptyList()
        _state.value = ConnState.SCANNING
        bleManager.scan(object : BleScanCallback() {
            override fun onScanStarted(success: Boolean) {}
            override fun onScanning(bleDevice: BleDevice) {
                // 厂商过滤：名非空 + 广播记录识别字节（首 AD 段长度偏移法）
                val rec = bleDevice.scanRecord ?: return
                if (rec.isEmpty() || rec.size < 8) return
                val name = bleDevice.name ?: return
                if (name.isEmpty()) return
                val len = rec[0].toInt()
                val p1 = len + 3
                val p2 = len + 4
                if (p2 >= rec.size) return
                if (rec[p1] != colorByte1 || rec[p2] != colorByte2) return
                val lamp = ScannedLamp(name, bleDevice.mac, bleDevice.rssi)
                seenDevices[bleDevice.mac] = bleDevice
                if (seen.put(lamp.mac, lamp) == null) {
                    _scanned.value = seen.values.toList()
                }
            }
            override fun onScanFinished(scanResult: List<BleDevice>) {
                if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
            }
        })
    }

    override fun stopScan() {
        bleManager.cancelScan()
        if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
    }

    private var current: BleDevice? = null

    override fun connect(lamp: ScannedLamp) {
        val ble = seenDevices[lamp.mac] ?: return
        _state.value = ConnState.CONNECTING
        bleManager.connect(ble, object : BleGattCallback() {
            override fun onStartConnect() {}
            override fun onConnectFail(bleDevice: BleDevice, exception: BleException) {
                _state.value = ConnState.DISCONNECTED
            }
            override fun onConnectSuccess(bleDevice: BleDevice, gatt: android.bluetooth.BluetoothGatt, status: Int) {
                current = bleDevice
                _state.value = ConnState.CONNECTED
                // 连接即注册 notify（对齐厂商 MyApplication$3 路径）
                bleManager.notify(bleDevice, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID, object : com.clj.fastble.callback.BleNotifyCallback() {
                    override fun onNotifySuccess() {}
                    override fun onNotifyFailure(exception: BleException) {}
                    override fun onCharacteristicChanged(data: ByteArray) {}
                })
            }
            override fun onDisConnected(
                isActiveDisConnected: Boolean,
                bleDevice: BleDevice,
                gatt: android.bluetooth.BluetoothGatt,
                status: Int
            ) {
                current = null
                _state.value = ConnState.DISCONNECTED
            }
        })
    }

    private val seenDevices = HashMap<String, BleDevice>()

    override fun disconnect() {
        current?.let { bleManager.disconnect(it) }
        current = null
        _state.value = ConnState.DISCONNECTED
    }

    override fun send(frame: ByteArray) {
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        val devices = bleManager.allConnectedDevice
        for (d in devices) {
            bleManager.write(
                d, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID, frame,
                /* split  */ true,
                /* seq     */ true,
                /* timeout */ 200L,
                object : BleWriteCallback() {
                    override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray) {}
                    override fun onWriteFailure(exception: BleException) {
                        Log.d("BLE-WRITE", "WriteFailure")
                    }
                }
            )
        }
    }

    override fun queryTimer(open: Boolean, onResult: (ByteArray?) -> Unit) {
        send(LampFrames.timerQuery(open))
        val device = current ?: run { onResult(null); return }
        bleManager.read(device, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID, object : BleReadCallback() {
            override fun onReadSuccess(data: ByteArray) = onResult(data)
            override fun onReadFailure(exception: BleException) = onResult(null)
        })
    }
}
