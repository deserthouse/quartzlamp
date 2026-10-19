package io.github.deserthouse.quartzlamp.ble

import android.util.Log
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleGattCallback
import com.clj.fastble.callback.BleNotifyCallback
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
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import java.util.Calendar

/**
 * FastBle 2.3.4 厂商同款实现（初始化参数照抄 docs/ble_protocol.md §1）。
 * 变量隔离决策（2026-10-15）：传输层保持厂商已验证组合，故障定位时嫌疑最小。
 *
 * 会话可靠性（2026-10-19 R19-03/04/07 复审修复）：
 * - 扫描是独立事实（_scanning），聚合态不再自证"仍在扫描"——超时/停止/启动失败三路都能归位；
 * - 每设备 [GattOpQueue] 串行队列：notify/对时/控制写/定时查询统一排队，一次一在途；
 *   律动帧 conflate（最新值优先）；普通写不再穿插查询事务；
 * - 连接世代守卫：disconnect(mac) 使在途 connect 回调过期，迟到的 onConnectSuccess 被拒绝并关 gatt。
 */
class FastBleLampConnection(private val app: android.app.Application) : LampConnection {

    private val bleManager = BleManager.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    override val state: StateFlow<ConnState> = _state

    /** 扫描独立事实（R19-03）——聚合态据此推导，不再读聚合值自身 */
    private val _scanning = MutableStateFlow(false)

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

    /** mac → 每设备操作队列（R19-04） */
    private val queues = HashMap<String, GattOpQueue>()
    private val queueJobs = HashMap<String, Job>()

    private val reconnectJobs = HashMap<String, Job>()

    /** 主动断开的设备不重连（F07） */
    private val manualDisconnects = HashSet<String>()

    /** 连接世代（R19-07）：disconnect 递增使在途 connect 回调过期 */
    private val connectGen = HashMap<String, Long>()

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

    // ━━━ 扫描（独立事实；与连接互不干扰：已连接也可扫） ━━━

    private val seen = LinkedHashMap<String, ScannedLamp>()

    override fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        seen.clear()
        _scanned.value = emptyList()
        _state.value = ConnState.SCANNING
        bleManager.scan(object : BleScanCallback() {
            override fun onScanStarted(success: Boolean) {
                if (!success) {
                    // 启动失败（蓝牙关闭/栈冲突）：清扫描事实归位（UI 另有蓝牙开关卡）
                    Log.w("BLE", "scan start failure")
                    _scanning.value = false
                    _state.value = aggregatedState()
                }
            }
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
                // 超时自然结束：清扫描事实再聚合（会话连接不受影响）
                _scanning.value = false
                _state.value = aggregatedState()
            }
        })
    }

    override fun stopScan() {
        bleManager.cancelScan()
        // 手动停止：清扫描事实再聚合——不误杀已建立连接，也绝不残留 SCANNING（R19-03）
        _scanning.value = false
        _state.value = aggregatedState()
    }

    // ━━━ 每设备会话 ━━━

    override fun connect(lamp: ScannedLamp) {
        val ble = seenDevices[lamp.mac] ?: return
        val gen = (connectGen[lamp.mac] ?: 0L) + 1
        connectGen[lamp.mac] = gen
        manualDisconnects.remove(lamp.mac)
        reconnectJobs.remove(lamp.mac)?.cancel()
        setSession(lamp.mac, SessionState.CONNECTING)
        _state.value = aggregatedState()
        bleManager.connect(ble, gattCallback(lamp.mac, gen))
    }

    override fun disconnect(mac: String) {
        manualDisconnects.add(mac)
        // 世代+1：在途 connect 的迟到 onConnectSuccess 将被拒绝（FastBle 2.3.4 无取消连接 API）
        connectGen[mac] = (connectGen[mac] ?: 0L) + 1
        reconnectJobs.remove(mac)?.cancel()
        teardownQueue(mac)
        devices.remove(mac)?.let { bleManager.disconnect(it) }
        setSession(mac, SessionState.DISCONNECTED)
        _state.value = aggregatedState()
    }

    private fun gattCallback(mac: String, gen: Long) = object : BleGattCallback() {
        override fun onStartConnect() {}
        override fun onConnectFail(bleDevice: BleDevice, exception: BleException) {
            setSession(mac, SessionState.DISCONNECTED)
            _state.value = aggregatedState()
        }

        override fun onConnectSuccess(bleDevice: BleDevice, gatt: android.bluetooth.BluetoothGatt, status: Int) {
            if (connectGen[mac] != gen || mac in manualDisconnects) {
                // 过时成功（连接期间被主动断开）：拒绝接管并关 gatt（R19-07）
                Log.w("BLE", "[$mac] stale connect success rejected (gen mismatch)")
                try {
                    bleManager.disconnect(bleDevice)
                } catch (_: Exception) {
                }
                return
            }
            devices[mac] = bleDevice
            setSession(mac, SessionState.CONNECTED)
            _state.value = aggregatedState()

            // 每设备串行队列接管该设备全部 GATT 操作（R19-04）
            val q = GattOpQueue(mac, transportOf(bleDevice))
            queues[mac] = q
            queueJobs[mac] = q.start(scope)
            scope.launch {
                // notify → 对时，顺序由队列保证（对齐厂商 MyApplication$3 + TimerUtils 路径）
                if (q.notify()) {
                    val c = Calendar.getInstance()
                    q.post(
                        LampFrames.timeSync(
                            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                            c.get(Calendar.SECOND), c.get(Calendar.DAY_OF_WEEK) - 1
                        )
                    )
                }
            }
        }

        override fun onDisConnected(
            isActiveDisConnected: Boolean,
            bleDevice: BleDevice,
            gatt: android.bluetooth.BluetoothGatt,
            status: Int
        ) {
            devices.remove(mac)
            teardownQueue(mac)
            if (mac in manualDisconnects || isActiveDisConnected) {
                setSession(mac, SessionState.DISCONNECTED)
                _state.value = aggregatedState()
            } else {
                // 意外断连：有限退避重连（3 次 × 4s，可被 disconnect() 取消）
                setSession(mac, SessionState.RECONNECTING)
                _state.value = aggregatedState()
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
                bleManager.connect(ble, gattCallback(mac, connectGen[mac] ?: 0L))
                // 连接发起后由回调接管状态；若 CONNECTING 维持到下个间隔则继续下一次
                delay(RECONNECT_INTERVAL_MS)
                if (_sessions.value[mac] == SessionState.CONNECTED) return@launch
            }
            if (_sessions.value[mac] == SessionState.RECONNECTING) {
                setSession(mac, SessionState.DISCONNECTED)
                _state.value = aggregatedState()
            }
        }
        reconnectJobs[mac] = job
    }

    // ━━━ 写（每设备队列串行；律动 conflate）与定时查询事务 ━━━

    override fun send(frame: ByteArray) {
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        recordFrame(hex)
        // 帧#6（BC 09）律动流 → conflate；其余控制帧按序排队（R19-04）
        val reactive = frame.size > 1 && frame[0] == 0xBC.toByte() && frame[1] == 0x09.toByte()
        for ((mac, q) in queues.toList()) { // toList：防迭代中 teardown 并发修改
            if (reactive) q.postReactive(frame) else q.post(frame)
        }
    }

    override suspend fun queryTimer(open: Boolean): ByteArray? = try {
        withTimeout(TX_TIMEOUT_MS) {
            val mac = _sessions.value.filterValues { it == SessionState.CONNECTED }.keys.firstOrNull()
                ?: return@withTimeout null
            val q = queues[mac] ?: return@withTimeout null
            val query = LampFrames.timerQuery(open)
            val hex = LampFrames.hex(query)
            Log.d("BLE-WRITE", hex)
            LampConnection.sharedFrameLog.tryEmit(hex)
            recordFrame(hex)
            // 写→读为单个队列操作：不会被普通写穿插（复审探针反例的修复点）
            q.query(query)
        }
    } catch (e: TimeoutCancellationException) {
        Log.w("BLE", "TimerQuery timeout")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("BLE", "TimerQuery fail: ${e.message}")
        null
    }

    // ━━━ 内部 ━━━

    private fun transportOf(d: BleDevice) = object : GattTransport {
        override fun enableNotify(done: (Boolean, String?) -> Unit) {
            bleManager.notify(d, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID,
                object : BleNotifyCallback() {
                    override fun onNotifySuccess() { done(true, null) }
                    override fun onNotifyFailure(exception: BleException) { done(false, exception.description) }
                    override fun onCharacteristicChanged(data: ByteArray) {}
                })
        }
        override fun write(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            bleManager.write(
                d, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID, frame,
                /* split */ true,
                /* sendNextWhenLastSuccess */ true,
                /* intervalBetweenTwoPackage */ 200L,
                object : BleWriteCallback() {
                    override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray) { done(true, null) }
                    override fun onWriteFailure(exception: BleException) { done(false, exception.description) }
                }
            )
        }
        override fun read(done: (ByteArray?, String?) -> Unit) {
            bleManager.read(d, LampFrames.SERVICE_UUID, LampFrames.CHAR_UUID,
                object : BleReadCallback() {
                    override fun onReadSuccess(data: ByteArray) = done(data, null)
                    override fun onReadFailure(exception: BleException) = done(null, exception.description)
                })
        }
    }

    private fun teardownQueue(mac: String) {
        queueJobs.remove(mac)?.cancel()
        queues.remove(mac)
    }

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
        _scanning.value -> ConnState.SCANNING
        _sessions.value.containsValue(SessionState.CONNECTING) ||
            _sessions.value.containsValue(SessionState.RECONNECTING) -> ConnState.CONNECTING
        else -> ConnState.DISCONNECTED
    }

    companion object {
        const val RECONNECT_ATTEMPTS = 3
        const val RECONNECT_INTERVAL_MS = 4_000L
        const val TX_TIMEOUT_MS = 20_000L
    }
}
