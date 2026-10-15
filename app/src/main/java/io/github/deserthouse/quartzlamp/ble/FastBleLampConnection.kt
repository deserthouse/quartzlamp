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
import io.github.deserthouse.quartzlamp.protocol.ScanFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FastBle 2.3.4 厂商同款实现（初始化参数照抄 docs/ble_protocol.md §1）。
 * 变量隔离决策（2026-10-15）：传输层保持厂商已验证组合，故障定位时嫌疑最小。
 *
 * 会话可靠性（审查 F06/F07）：
 * - 每 MAC 会话状态（sessions），扫描与连接是独立维度——已连接也能继续扫描；
 * - 意外断连有限退避重连（3 次 × 4s，厂商 setReConnectCount 同参），主动断开不重连；
 * - 定时查询走串行事务：写→等写回调→读，Mutex 单在途 + withTimeout；
 * - 连接成功即同步手机时钟（帧#14，TimerUtils.updatePhoenTimerToLight 厂商同语义）。
 */
class FastBleLampConnection(private val app: android.app.Application) : LampConnection {

    private val bleManager = BleManager.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** mac → 扫描期见的设备句柄 */
    private val seenDevices = HashMap<String, BleDevice>()

    /** mac → 已连接设备句柄 */
    private val devices = HashMap<String, BleDevice>()

    private val reconnectJobs = HashMap<String, Job>()

    /** 主动断开的设备不重连（F07） */
    private val manualDisconnects = HashSet<String>()

    /** 定时查询串行事务锁（同一时刻至多一个在途） */
    private val timerTx = Mutex()

    fun init() {
        // 厂商顺序（MyApplication.onCreate:0007-0023）：init(app) 必须最先——
        // 缺它时首次扫描在 BleScanner 内 NPE（审查 F01，真机阻断）
        bleManager.init(app)
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

    // ━━━ 扫描（与连接独立维度：已连接也可扫） ━━━


    override fun startScan() {
        if (_state.value == ConnState.SCANNING) return
        seen.clear()
        _scanned.value = emptyList()
        _state.value = ConnState.SCANNING
        bleManager.scan(object : BleScanCallback() {
            override fun onScanStarted(success: Boolean) {}
            override fun onScanning(bleDevice: BleDevice) {
                // 厂商过滤：名非空 + 广播识别字节（纯函数在 protocol/ScanFilter，独立单测）
                val name = bleDevice.name ?: return
                if (name.isEmpty()) return
                if (!ScanFilter.isLampBroadcast(bleDevice.scanRecord)) return
                seenDevices[bleDevice.mac] = bleDevice
                val lamp = ScannedLamp(name, bleDevice.mac, bleDevice.rssi)
                if (seen.put(lamp.mac, lamp) == null) {
                    _scanned.value = seen.values.toList()
                }
            }
            override fun onScanFinished(scanResult: List<BleDevice>) {
                if (_state.value == ConnState.SCANNING) _state.value = aggregatedState()
            }
        })
    }

    override fun stopScan() {
        bleManager.cancelScan()
        // 只复位扫描态；不误杀已建立连接（离开设备页会触发 onDispose）
        if (_state.value == ConnState.SCANNING) _state.value = aggregatedState()
    }

    // ━━━ 每设备会话 ━━━

    private val seen = LinkedHashMap<String, ScannedLamp>()

    override fun connect(lamp: ScannedLamp) {
        val ble = seenDevices[lamp.mac] ?: return
        manualDisconnects.remove(lamp.mac)
        reconnectJobs.remove(lamp.mac)?.cancel()
        setSession(lamp.mac, SessionState.CONNECTING)
        bleManager.connect(ble, gattCallback(lamp.mac))
    }

    override fun disconnect(mac: String) {
        manualDisconnects.add(mac)
        reconnectJobs.remove(mac)?.cancel()
        devices[mac]?.let { bleManager.disconnect(it) }
        devices.remove(mac)
        setSession(mac, SessionState.DISCONNECTED)
        refreshAggregated()
    }

    private fun gattCallback(mac: String) = object : BleGattCallback() {
        override fun onStartConnect() {}
        override fun onConnectFail(bleDevice: BleDevice, exception: BleException) {
            setSession(mac, SessionState.DISCONNECTED)
            refreshAggregated()
        }

        override fun onConnectSuccess(bleDevice: BleDevice, gatt: android.bluetooth.BluetoothGatt, status: Int) {
            devices[mac] = bleDevice
            setSession(mac, SessionState.CONNECTED)
            refreshAggregated()
            // 连接即注册 notify（对齐厂商 MyApplication$3 路径）
            bleManager.notify(bleDevice, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID,
                object : com.clj.fastble.callback.BleNotifyCallback() {
                    override fun onNotifySuccess() {}
                    override fun onNotifyFailure(exception: BleException) {}
                    override fun onCharacteristicChanged(data: ByteArray) {}
                })
            // 连接成功即同步手机时钟（厂商 TimerUtils.updatePhoenTimerToLight 同语义，F06）
            val c = Calendar.getInstance()
            send(
                LampFrames.timeSync(
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                    c.get(Calendar.SECOND), c.get(Calendar.DAY_OF_WEEK) - 1
                )
            )
        }

        override fun onDisConnected(
            isActiveDisConnected: Boolean,
            bleDevice: BleDevice,
            gatt: android.bluetooth.BluetoothGatt,
            status: Int
        ) {
            devices.remove(mac)
            if (mac in manualDisconnects || isActiveDisConnected) {
                setSession(mac, SessionState.DISCONNECTED)
                refreshAggregated()
            } else {
                // 意外断连：有限退避重连（3 次 × 4s，可被 disconnect() 取消）
                setSession(mac, SessionState.RECONNECTING)
                refreshAggregated()
                scheduleReconnect(mac)
            }
        }
    }

    private fun scheduleReconnect(mac: String) {
        val job = scope.launch {
            repeat(RECONNECT_ATTEMPTS) {
                delay(RECONNECT_INTERVAL_MS)
                val ble = seenDevices[mac] ?: return@launch
                if (_sessions.value[mac] != SessionState.RECONNECTING) return@launch
                bleManager.connect(ble, gattCallback(mac))
                // 连接发起后由回调接管状态；若 CONNECTING 维持到下个间隔则继续下一次
                delay(RECONNECT_INTERVAL_MS)
                if (_sessions.value[mac] == SessionState.CONNECTED) return@launch
            }
            if (_sessions.value[mac] == SessionState.RECONNECTING) {
                setSession(mac, SessionState.DISCONNECTED)
                refreshAggregated()
            }
        }
        reconnectJobs[mac] = job
    }

    // ━━━ 写（广播）与串行查询事务 ━━━

    override fun send(frame: ByteArray) {
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        recordFrame(hex)
        for (d in bleManager.allConnectedDevice) {
            bleManager.write(
                d, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID, frame,
                /* split */ true,
                /* sendNextWhenLastSuccess */ true,
                /* intervalBetweenTwoPackage */ 200L,
                object : BleWriteCallback() {
                    override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray) {}
                    override fun onWriteFailure(exception: BleException) {
                        Log.d("BLE-WRITE", "WriteFailure")
                    }
                }
            )
        }
    }

    override suspend fun queryTimer(open: Boolean): ByteArray? = try {
        withTimeout(TX_TIMEOUT_MS) {
            timerTx.withLock {
                val mac = _sessions.value.filterValues { it == SessionState.CONNECTED }.keys.firstOrNull()
                    ?: return@withLock null
                val device = devices[mac] ?: return@withLock null

                val query = LampFrames.timerQuery(open)
                val hex = LampFrames.hex(query)
                Log.d("BLE-WRITE", hex)
                LampConnection.sharedFrameLog.tryEmit(hex)
                recordFrame(hex)

                // 写 → 等本帧写回调（FastBle 内部队列保证排在前序帧之后）→ 读
                awaitWrite(device, query)
                suspendCancellableCoroutine { cont ->
                    bleManager.read(device, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID,
                        object : BleReadCallback() {
                            override fun onReadSuccess(data: ByteArray) = cont.resume(data)
                            override fun onReadFailure(exception: BleException) =
                                // BleException 不继承 Throwable（FastBle 2.3.4 实现 Serializable），包装上抛
                                cont.resumeWithException(RuntimeException("BleException " + exception.code + ": " + exception.description))
                        })
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        // 超时 → 错误通道返回 null（不静默挂起）；取消类异常原样上抛保结构化并发
        Log.d("BLE-WRITE", "TimerQueryTimeout")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d("BLE-WRITE", "TimerQueryFail: ${e.message}")
        null
    }

    private suspend fun awaitWrite(device: BleDevice, frame: ByteArray) {
        suspendCancellableCoroutine { cont ->
            bleManager.write(
                device, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID, frame,
                true, true, 200L,
                object : BleWriteCallback() {
                    override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray) =
                        cont.resume(Unit)
                    override fun onWriteFailure(exception: BleException) =
                        cont.resumeWithException(RuntimeException("BleException " + exception.code + ": " + exception.description))
                }
            )
        }
    }

    // ━━━ 内部 ━━━

    private fun recordFrame(hex: String) {
        synchronized(history) {
            history.addLast(hex)
            while (history.size > LampConnection.FRAME_HISTORY_LIMIT) history.removeFirst()
        }
        _frameHistory.value = synchronized(history) { history.toList() }
    }

    private fun setSession(mac: String, s: SessionState) {
        _sessions.value = _sessions.value.toMutableMap().also { it[mac] = s }
    }

    private fun aggregatedState(): ConnState = when {
        _sessions.value.containsValue(SessionState.CONNECTED) -> ConnState.CONNECTED
        _state.value == ConnState.SCANNING -> ConnState.SCANNING
        _sessions.value.containsValue(SessionState.CONNECTING) ||
            _sessions.value.containsValue(SessionState.RECONNECTING) -> ConnState.CONNECTING
        else -> ConnState.DISCONNECTED
    }

    private fun refreshAggregated() {
        if (_state.value != ConnState.SCANNING) _state.value = aggregatedState()
    }

    companion object {
        const val RECONNECT_ATTEMPTS = 3
        const val RECONNECT_INTERVAL_MS = 4_000L
        const val TX_TIMEOUT_MS = 12_000L
    }
}
