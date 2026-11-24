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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.Calendar
import kotlin.coroutines.coroutineContext

/**
 * FastBle 入口缝（扫描/连接发起面）——默认直连 BleManager，生产路径不变。
 * 存在理由：会话状态机（世代守卫/重连预算/终态归属）此前只能在真机上验证；JVM 单测注入
 * 脚本化实现即可驱动 gatt 回调对真实类做回归。BleManager 的其余入口（notify/write/read/disconnect）保持直调。
 */
internal interface BleGateway {
    fun scan(callback: BleScanCallback)
    fun cancelScan()
    fun connect(device: BleDevice, callback: BleGattCallback)

    /**
     * 陈旧连接成功的善后。FastBle 按设备键维护"已连接表"且**先到先得**（MultipleBluetoothController.addBleBluetooth：
     * 键已存在就不登记）。"连接中点取消"取消不了传输层的在途连接（FastBle 2.3.4 无此 API）——取消后立刻再连，
     * 旧尝试若抢在新尝试之前连上，会占住表位；我方陈旧分支随即关掉它的 gatt，而新尝试连上后因键被占**不被登记**，
     * 此后 notify/write/read 全部路由到那个已关闭的旧句柄：会话显示"已连接"但操作全部失败，直到进程重启。
     * 若表内句柄正是 [gatt] 本人则移除（把表位让给新尝试），返回是否移除；不是本人则不动（表里是合法的新会话）。
     */
    fun evictConnectedIfOwnedBy(device: BleDevice, gatt: android.bluetooth.BluetoothGatt): Boolean
}

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
class FastBleLampConnection private constructor(
    private val app: android.app.Application?,
    gateway: BleGateway?,
) : LampConnection {

    constructor(app: android.app.Application) : this(app, null)

    /** 单测入口：注入脚本化网关，在 JVM 上驱动扫描/gatt 回调（见 [BleGateway]） */
    internal constructor(gateway: BleGateway) : this(null, gateway)

    private val bleManager = BleManager.getInstance()

    /** FastBle 入口：生产=直连 BleManager；单测=脚本化实现 */
    private val gw: BleGateway = gateway ?: object : BleGateway {
        override fun scan(callback: BleScanCallback) {
            bleManager.scan(callback)
        }

        override fun cancelScan() {
            bleManager.cancelScan()
        }

        override fun connect(device: BleDevice, callback: BleGattCallback) {
            bleManager.connect(device, callback)
        }

        override fun evictConnectedIfOwnedBy(
            device: BleDevice,
            gatt: android.bluetooth.BluetoothGatt,
        ): Boolean {
            // 均为 FastBle 公开方法，不改收编源
            val controller = bleManager.multipleBluetoothController ?: return false
            val registered = controller.getBleBluetooth(device) ?: return false
            if (registered.bluetoothGatt !== gatt) return false
            controller.removeBleBluetooth(registered)
            return true
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    override val state: StateFlow<ConnState> = _state

    /** 扫描独立事实（R19-03）——聚合态据此推导，不再读聚合值自身 */
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning

    private val _scanned = MutableStateFlow<List<ScannedLamp>>(emptyList())
    override val scanned: StateFlow<List<ScannedLamp>> = _scanned

    override val frameLog: SharedFlow<String> = LampConnection.sharedFrameLog

    private val _frameHistory = MutableStateFlow<List<String>>(emptyList())
    override val frameHistory: StateFlow<List<String>> = _frameHistory
    private val history = ArrayDeque<String>()

    /** 每设备会话状态（mac → state），扫描列表行级操作依据（F07） */
    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessions: StateFlow<Map<String, SessionState>> = _sessions

    /** 会话就绪事件（AV04-08；Q2 携带会话身份） */
    private val _sessionReady = MutableSharedFlow<SessionReadyEvent>(extraBufferCapacity = 16)
    override val sessionReady: SharedFlow<SessionReadyEvent> = _sessionReady

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

    /** 连接世代（R19-07/R20-01）：disconnect 递增使全部在途回调过期 */
    private val connectGen = HashMap<String, Long>()

    /**
     * 尝试世代（R20-01）：每次 connect 尝试分配单调递增 id；
     * 全部 gatt 回调先比对 attempt==attemptGen[mac]，陈旧回调零影响。
     */
    private val attemptGen = HashMap<String, Long>()

    /** 最近一次连接尝试的终态信号（成功=true/失败=false），重连循环据此串行推进 */
    private val attemptTerminal = HashMap<String, CompletableDeferred<Boolean>>()

    fun init() {
        // 厂商顺序（MyApplication.onCreate:0007-0023）：init(app) 必须最先——
        // 缺它时首次扫描在 BleScanner 内 NPE（审查 F01，真机阻断）
        bleManager.init(app)
        bleManager
            .enableLog(true)
            .setReConnectCount(FB_RECONNECT_COUNT, FB_RECONNECT_INTERVAL_MS)
            .setMaxConnectCount(7)
            .setConnectOverTime(FB_CONNECT_OVERTIME_MS)
            .setOperateTimeout(5000)
        bleManager.initScanRule(
            BleScanRuleConfig.Builder()
                // 直连（非厂商值 true，厂商为后台自动连接）——2026-11-24 审查登记的偏离项，
                // 现值经真灯验证可连；事实与待决记录见 docs/ble_protocol.md §1
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
        gw.scan(object : BleScanCallback() {
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
        gw.cancelScan()
        // 手动停止：清扫描事实再聚合——不误杀已建立连接，也绝不残留 SCANNING（R19-03）
        _scanning.value = false
        _state.value = aggregatedState()
    }

    // ━━━ 每设备会话 ━━━

    override fun connect(lamp: ScannedLamp) {
        val ble = seenDevices[lamp.mac] ?: return
        // 会话已在途/已连接时忽略：仅 DISCONNECTED 行提供"连接"。FastBle 2.3.4 无取消连接 API，
        // 对在途尝试再发 connect 会产生并行 connectGatt——彼此干扰，先败的一方还会把会话打成未连接
        when (_sessions.value[lamp.mac]) {
            SessionState.CONNECTING, SessionState.RECONNECTING, SessionState.CONNECTED -> return
            else -> Unit
        }
        // 用户发起=接管：该 MAC 的既有重连任务（若有）终止，由本次尝试独立推进
        reconnectJobs.remove(lamp.mac)?.cancel()
        val attempt = nextAttempt(lamp.mac)
        manualDisconnects.remove(lamp.mac)
        attemptTerminal[lamp.mac] = CompletableDeferred()
        setSession(lamp.mac, SessionState.CONNECTING)
        _state.value = aggregatedState()
        gw.connect(ble, gattCallback(lamp.mac, attempt))
    }

    /** 每次尝试分配单调 attempt id；全部回调按此判陈旧 */
    private fun nextAttempt(mac: String): Long {
        val next = (attemptGen[mac] ?: 0L) + 1L
        attemptGen[mac] = next
        return next
    }

    override fun disconnect(mac: String) {
        manualDisconnects.add(mac)
        // attempt+connectGen 双递增：在途 connect 的全部迟到回调（成功/失败/断开）都被判陈旧
        // （FastBle 2.3.4 无取消连接 API）
        connectGen[mac] = (connectGen[mac] ?: 0L) + 1
        nextAttempt(mac)
        attemptTerminal.remove(mac)?.complete(false)
        reconnectJobs.remove(mac)?.cancel()
        teardownQueue(mac)
        devices.remove(mac)?.let { bleManager.disconnect(it) }
        setSession(mac, SessionState.DISCONNECTED)
        _state.value = aggregatedState()
    }

    private fun gattCallback(mac: String, attempt: Long) = object : BleGattCallback() {
        /** R20-01：陈旧回调（非当前尝试）零影响——不改会话态/队列/句柄 */
        private fun stale() = attempt != (attemptGen[mac] ?: -1L) || mac in manualDisconnects

        override fun onStartConnect() {}
        override fun onConnectFail(bleDevice: BleDevice, exception: BleException) {
            if (stale()) return
            attemptTerminal[mac]?.complete(false)
            // R44-02：重连预算内失败由重连循环自持推进（单次失败不得终结预算）；
            // 首次连接失败（无重连任务）才落 DISCONNECTED
            if (reconnectJobs[mac] == null) {
                setSession(mac, SessionState.DISCONNECTED)
                _state.value = aggregatedState()
            }
        }

        // R44-03：本分支直接操作回调持有的 gatt（disconnect/close）——
        // 权限由连接建立路径统一保证，lint 提示对齐 libs-src 既有豁免口径
        @Suppress("MissingPermission")
        override fun onConnectSuccess(
            bleDevice: BleDevice,
            gatt: android.bluetooth.BluetoothGatt?,
            status: Int
        ) {
            if (stale()) {
                // R44-03：过时成功只关闭**本回调持有的旧 gatt**（精确句柄）——
                // 不经 bleManager 按键查找，避免误断同键已易主的新会话
                Log.w("BLE", "[$mac] stale connect success rejected (owned gatt)")
                try {
                    // 先让出传输层"已连接表"的表位，再关句柄（顺序不能反，见 BleGateway.evictConnectedIfOwnedBy）
                    gatt?.let { gw.evictConnectedIfOwnedBy(bleDevice, it) }
                    gatt?.disconnect()
                    gatt?.close()
                } catch (_: Exception) {
                }
                return
            }
            attemptTerminal[mac]?.complete(true)
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
            // 会话就绪事件（AV04-08；Q2 携带 sessionId）：每灯独立发出
            _sessionReady.tryEmit(SessionReadyEvent(mac, attempt))
        }

        override fun onDisConnected(
            isActiveDisConnected: Boolean,
            bleDevice: BleDevice,
            gatt: android.bluetooth.BluetoothGatt?,
            status: Int
        ) {
            if (stale()) return // 陈旧断开回调零影响（R20-01）
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

    /**
     * 有限重连：单 Job 串行推进——每次尝试等待其**终态**（成功/失败；兜底见 [CONNECT_ATTEMPT_TIMEOUT_MS]）
     * 再退避重试，尝试间不重叠；attemptGen 递增使上一尝试的迟到回调全部失效。
     *
     * 任务登记顺序（2026-11 审查修复）：scope 是 Main.immediate，本函数由 FastBle 的主线程回调调用，
     * 默认 launch 会把协程体同步跑到首个挂起点；循环首轮要读 reconnectJobs[mac] 判断"自己是否仍是
     * 当前任务"，若此刻尚未登记就会读到旧值并直接 break——重连一次都不发起。故 LAZY 创建 → 先登记 → 再启动。
     * 任务结束（任何出口）按身份清表：表内只表示"活着的重连任务"，onConnectFail 据此区分首次连接。
     */
    private fun scheduleReconnect(mac: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var attempts = 0
                while (attempts < RECONNECT_ATTEMPTS) {
                    if (mac in manualDisconnects || reconnectJobs[mac] !== currentCoroutineContext()[Job]) break
                    delay(RECONNECT_INTERVAL_MS)
                    if (mac in manualDisconnects || reconnectJobs[mac] !== currentCoroutineContext()[Job]) break
                    val ble = seenDevices[mac] ?: break
                    attempts++
                    val attempt = nextAttempt(mac)
                    val terminal = CompletableDeferred<Boolean>()
                    attemptTerminal[mac] = terminal
                    setSession(mac, SessionState.CONNECTING)
                    _state.value = aggregatedState()
                    // 传输层入口若同步抛异常，按本次尝试失败计：取消原样传播；其余异常不能让后台任务带着
                    // 异常终止（那会走线程未捕获处理器，且会话停在 CONNECTING、重连预算也随之作废）
                    val ok = try {
                        gw.connect(ble, gattCallback(mac, attempt))
                        withTimeoutOrNull(CONNECT_ATTEMPT_TIMEOUT_MS) { terminal.await() } ?: false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("BLE", "[$mac] reconnect attempt threw: $e")
                        false
                    }
                    if (ok) return@launch // onConnectSuccess 已置 CONNECTED
                    // R44-02：单次失败不终结预算——循环继续（退避在轮首 delay）
                }
                // 预算耗尽/被取代/手动断开：非 CONNECTED 才落 DISCONNECTED
                if (reconnectJobs[mac] === currentCoroutineContext()[Job] &&
                    mac !in manualDisconnects && _sessions.value[mac] != SessionState.CONNECTED
                ) {
                    setSession(mac, SessionState.DISCONNECTED)
                    _state.value = aggregatedState()
                }
            } finally {
                // 仅当表中仍是自己时才清（被 disconnect()/新任务替换过的条目不归本任务管）
                if (reconnectJobs[mac] === coroutineContext[Job]) reconnectJobs.remove(mac)
            }
        }
        reconnectJobs[mac] = job
        job.start()
    }

    // ━━━ 写（每设备队列串行；律动 conflate）与定时查询事务 ━━━

    override suspend fun sendTo(mac: String, frame: ByteArray): Boolean {
        val q = queues[mac] ?: return false
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        recordFrame(hex)
        return q.write(frame)
    }

    override suspend fun sendDetailed(frame: ByteArray): DeliveryReport {
        val hex = LampFrames.hex(frame)
        Log.d("BLE-WRITE", hex)
        LampConnection.sharedFrameLog.tryEmit(hex)
        recordFrame(hex)
        // 帧#6（BC 09）律动流 → conflate；其余控制帧按序排队（R19-04）
        val reactive = frame.size > 1 && frame[0] == 0xBC.toByte() && frame[1] == 0x09.toByte()
        // S1：目标集合发起时冻结——新加入灯不属于本次操作目标
        val targets = _sessions.value.filterValues { it == SessionState.CONNECTED }.keys.toSet()
        if (targets.isEmpty()) return DeliveryReport(emptySet(), emptyMap())
        val results = HashMap<String, Boolean>()
        for (mac in targets) {
            val q = queues[mac] ?: run { results[mac] = false; continue }
            results[mac] = if (reactive) q.postReactiveAwait(frame) else q.write(frame)
        }
        return DeliveryReport(targets, results)
    }

    override suspend fun send(frame: ByteArray): SendResult = sendDetailed(frame).toSendResult()

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
        queues.remove(mac)?.close() // R20-06：失败全部等待者，不再悬留
        queueJobs.remove(mac)?.cancel()
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

        /** FastBle 初始化参数（厂商同款，docs/ble_protocol.md §1）——init() 与兜底超时共用同一来源 */
        private const val FB_RECONNECT_COUNT = 3
        private const val FB_RECONNECT_INTERVAL_MS = 4_000L
        private const val FB_CONNECT_OVERTIME_MS = 99_000L

        /**
         * 单轮重连的终态兜底超时。一轮 = 我方一次 connect 调用；FastBle 在其**内部**还会对连接失败
         * 重试 [FB_RECONNECT_COUNT] 次（BleBluetooth.MSG_CONNECT_FAIL），每次最长 [FB_CONNECT_OVERTIME_MS]，
         * 整段序列都失败才回调 onConnectFail。兜底必须覆盖整段序列——否则我方会在 FastBle 仍在重试时
         * 叠发新连接（并行 connectGatt）。正常情况下终态先到，兜底不参与计时。
         */
        const val CONNECT_ATTEMPT_TIMEOUT_MS: Long =
            (FB_RECONNECT_COUNT + 1) * FB_CONNECT_OVERTIME_MS +
                FB_RECONNECT_COUNT * FB_RECONNECT_INTERVAL_MS + 12_000L
    }
}
