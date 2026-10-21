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

/**
 * 逐灯投递报告（S1/R44-04）：目标集合在发起时冻结，结果逐 MAC。
 * 聚合（All/Some/None）由数据推导，UI 不再消费折叠后的全局布尔位。
 */
data class DeliveryReport(
    val targets: Set<String>,
    val results: Map<String, Boolean>,
) {
    val allOk: Boolean get() = targets.isNotEmpty() && results.values.all { it }
    val failedMacs: Set<String> get() = results.filterValues { !it }.keys

    /** 兼容旧枚举（过渡期）：SendResult 语义保持不变 */
    fun toSendResult(): SendResult = when {
        allOk -> SendResult.SentToAll
        results.values.any { it } -> SendResult.SentToSome
        targets.isEmpty() -> SendResult.NoTarget
        else -> SendResult.Failed
    }
}

/** 会话就绪事件（Q2）：携带会话身份的初始化通知 */
data class SessionReadyEvent(val mac: String, val sessionId: Long)

/** 聚合连接态（供标题栏等单值场景）：任一设备 CONNECTED 即 CONNECTED */
enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

/** 单设备会话态（审查 F07：扫描与连接是两个独立状态维度，按 MAC 维护） */
enum class SessionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/** 投递结果（R20-03）：区分逐灯 GATT 完成/无目标/失败，禁止把意图当发送结果 */
enum class SendResult {
    /** 全部已连接灯写入成功（n≥1） */
    SentToAll,

    /** 部分灯成功（多灯部分失败） */
    SentToSome,

    /** 无已连接灯（命令未发出，只保留本地意图） */
    NoTarget,

    /** 全部失败 */
    Failed
}

/**
 * BLE 门面 —— UI 层唯一入口（FastBle 类永不进入 UI 层）。
 * 实现：FastBleLampConnection（真机，main）/ FakeLampConnection（AVD 自测，仅 debug 变体，F11）。
 * 日志契约：send() 打 BLE-WRITE 同格式日志（与原厂 App logcat 对照，验收定位用）。
 */
interface LampConnection {
    /** 聚合连接态 */
    val state: StateFlow<ConnState>
    val scanned: StateFlow<List<ScannedLamp>>

    /** 扫描独立事实（R20-07）：与连接态并行暴露，UI 转圈/停止据此而非聚合枚举 */
    val scanning: StateFlow<Boolean>

    /** BLE-WRITE 格式帧日志（hex 串）事件流——无订阅即丢 */
    val frameLog: SharedFlow<String>
    /** 有界会话帧记录（最新在尾，上限 [FRAME_HISTORY_LIMIT] 条）——持久诊断记录（F11） */
    val frameHistory: StateFlow<List<String>>
    /** 每设备会话状态（mac → state），扫描列表行级操作依据（F07） */
    val sessions: StateFlow<Map<String, SessionState>>
    /** 演示模式（假灯）标记；UI 显示徽标，release 实现恒 false（F11） */
    val isDemo: Boolean get() = false

    /**
     * 会话就绪事件（AV04-08；Q2 携带身份）：连接成功且初始化序列（notify→对时）已排队时发出。
     * 每灯独立触发——第二盏接入也会发；扫描起停不产生此事件。
     * sessionId=连接尝试 id（单调）——消费方可按会话身份去重/定向。
     */
    val sessionReady: SharedFlow<SessionReadyEvent>

    fun startScan()
    fun stopScan()

    /** 连接指定设备（多灯并存；意外断连有限退避重连，主动断开不重连——F07） */
    fun connect(lamp: ScannedLamp)

    /** 断开指定设备（主动；取消其重连计划） */
    fun disconnect(mac: String)

    /**
     * 定向写单设备（Q2）：初始化序列按 MAC 定向，不再向全部旧灯广播。
     * 会话不存在/未连接返回 false。
     */
    suspend fun sendTo(mac: String, frame: ByteArray): Boolean

    /**
     * 逐灯投递（S1）：目标集合=发起时全部 CONNECTED 会话（冻结），
     * 返回逐 MAC 真实结果。多灯部分失败不再折叠为全局成功。
     */
    suspend fun sendDetailed(frame: ByteArray): DeliveryReport

    /**
     * 下发帧（遍历所有已连接设备，多灯广播——对齐厂商行为）。
     * R20-03：挂起至逐灯 GATT 写完成，返回真实投递结果；无连接=NoTarget。
     */
    suspend fun send(frame: ByteArray): SendResult

    /**
     * 定时查询事务（F06）：查询帧写入 → 等待写回调 → GATT 读 → 全程超时保护，
     * 同一时刻至多一个在途事务。读自首个 CONNECTED 设备（多灯读取对象明确化，
     * 一致性核对留真灯验收）。失败/超时返回 null。
     */
    suspend fun queryTimer(open: Boolean): ByteArray?

    companion object {
        const val FRAME_HISTORY_LIMIT = 200

        /** 全局帧日志汇聚（两个实现共用），App 内诊断页可订阅 */
        val sharedFrameLog: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 64)
    }
}
