package io.github.deserthouse.quartzlamp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.quartzlamp.QuartzLampApp
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.SendResult
import io.github.deserthouse.quartzlamp.ble.LampConnection
import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import io.github.deserthouse.quartzlamp.ble.SessionState
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 灯模式（U3/D2：单色=静态颜色，灯效=效果编号——互斥的两种存在状态） */
sealed class LampMode {
    data class Static(val hue: Int, val sat: Float) : LampMode()
    data class Effect(val index: Int) : LampMode()
}

/**
 * 全局唯一 ViewModel：连接状态 + 各页控制语义（帧构造全走 protocol 层）。
 * 厂商默认值（ble_protocol.md §4）：speed=50, sensitivity=50。
 */
class AppViewModel(private val lamp: LampConnection) : ViewModel() {

    val state: StateFlow<ConnState> = lamp.state
    val scanned: StateFlow<List<ScannedLamp>> = lamp.scanned
    val scanning: StateFlow<Boolean> = lamp.scanning
    val sessions: StateFlow<Map<String, SessionState>> = lamp.sessions
    val frameLog get() = lamp.frameLog
    val isDemo get() = lamp.isDemo

    fun startScan() = lamp.startScan()
    fun stopScan() = lamp.stopScan()
    fun connect(l: ScannedLamp) = lamp.connect(l)
    fun disconnect(mac: String) = lamp.disconnect(mac)

    // ━━━ 电源 ━━━
    private val _powerOn = MutableStateFlow(true)
    val powerOn: StateFlow<Boolean> = _powerOn

    // ━━━ 调色 ━━━
    data class ColorState(val hue: Int = 210, val sat: Float = 1f, val bright: Int = 600)

    private val _color = MutableStateFlow(ColorState())
    val color: StateFlow<ColorState> = _color

    /** 灯模式（V/U3）：setColor(send)→Static；selectEffect→Effect。预览拖动不改模式。 */
    private val _lampMode = MutableStateFlow<LampMode>(LampMode.Effect(1)) // 厂商 initView 默认索引 1
    val lampMode: StateFlow<LampMode> = _lampMode

    /** 当前灯效帧是否真实投递成功（R20-03：运行中徽章的证据位） */
    private val _lastEffectSendOk = MutableStateFlow(false)
    val lastEffectSendOk: StateFlow<Boolean> = _lastEffectSendOk

    // 注意：不要在类头部放 init 副作用块——Kotlin 属性按声明序初始化，init 先于 _mic 等
    // 后声明字段执行，已连接+同步返回路径会读未初始化字段直接 NPE（AV04-01 实机崩溃）。
    // 会话就绪初始化的收集器在类尾部 init 启动（见文件底部）。

    /** 发送并按结果更新状态（R20-03：意图≠结果） */
    private fun fire(block: suspend () -> Unit) = viewModelScope.launch { block() }

    // ━━━ 模式 ━━━
    data class ModeState(
        val tab: Int = 0,
        val selected: Int = 1,
        val speed: Int = 50,
        val sensitivity: Int = 50
    )

    private val _mode = MutableStateFlow(ModeState())
    val mode: StateFlow<ModeState> = _mode

    // ━━━ 麦克风 ━━━
    data class MicState(
        val type: Int = 1,
        val sensitivity: Int = 50
    )

    private val _mic = MutableStateFlow(MicState())
    val mic: StateFlow<MicState> = _mic

    // ━━━ 定时（F05/F06 重做）━━━
    /**
     * 单时段槽位：草稿即状态（VM 独立持有，两槽互不干扰——读开灯不丢关灯草稿）。
     * loaded=灯端读到过有效配置；busy=查询中；error=最近一次读取失败。
     * 无"启用"开关：协议无此位（F05），保存即下发、删除=清空重存、读回空=未设置。
     */
    /**
     * 定时本次操作阶段（AV04-03 互斥模型）——历史观测（loaded/readFrom）独立保存，
     * 不再被旧 error 压制或互相覆盖。
     */
    enum class TimerPhase { Idle, Sent, NoTarget, Failed }

    data class TimerSlot(
        val hour: Int = 7,
        val minute: Int = 0,
        val weeks: List<Boolean> = List(7) { false },
        val busy: Boolean = false,        // 读取中
        val dirty: Boolean = false,       // 草稿：编辑过未应用（R19-09）
        val revision: Long = 0,           // 草稿修订号：读回/保存结果仅接受同修订（R20-03/AV04-03）
        val phase: TimerPhase = TimerPhase.Idle, // AV04-03：最近一次"应用到灯"的真实结果
        val loaded: Boolean = false,      // 灯端读到过有效配置（历史观测）
        val readFrom: String? = null      // 读取目标灯（多灯时=第一盏）
    )

    data class TimerUiState(
        val open: TimerSlot = TimerSlot(),
        val close: TimerSlot = TimerSlot(hour = 23, minute = 0)
    )

    private val _timer = MutableStateFlow(TimerUiState())
    val timer: StateFlow<TimerUiState> = _timer

    fun setPower(on: Boolean) {
        _powerOn.value = on
        fire { lamp.send(LampFrames.power(on)) }
    }

    fun setColor(hue: Int, sat: Float, send: Boolean = true) {
        _color.value = _color.value.copy(hue = hue, sat = sat)
        if (send) fire {
            when (lamp.send(LampFrames.colorStatic(hue, sat))) {
                SendResult.SentToAll, SendResult.SentToSome ->
                    _lampMode.value = LampMode.Static(hue, sat) // 投递成功才认单色模式（R20-03）
                else -> {} // NoTarget/Failed：保留原模式判断，不虚报
            }
        }
    }

    /**
     * 提交当前颜色（AV04-09）：滑杆松手/色环提交的权威入口——读 VM 当前值发送，
     * 不从 UI 闭包快照重建状态（快速点按不再把刚更新的值覆盖回旧值）。
     */
    fun commitColor() {
        val c = _color.value
        fire {
            when (lamp.send(LampFrames.colorStatic(c.hue, c.sat))) {
                SendResult.SentToAll, SendResult.SentToSome ->
                    _lampMode.value = LampMode.Static(c.hue, c.sat)
                else -> {}
            }
        }
    }

    fun updateBrightness(v: Int) {
        // 拖动中只更新本地状态；sendBrightness() 松手时发一帧（防写队列洪泛，对齐厂商行为）
        _color.value = _color.value.copy(bright = v)
    }

    fun sendBrightness() = fire { lamp.send(LampFrames.brightness(_color.value.bright)) }

    fun selectEffect(tab: Int, position: Int, deviceIndex: Int) {
        _mode.value = _mode.value.copy(tab = tab, selected = deviceIndex)
        fire {
            when (lamp.send(LampFrames.modeSelect(deviceIndex))) {
                SendResult.SentToAll, SendResult.SentToSome -> {
                    _lampMode.value = LampMode.Effect(deviceIndex)
                    _lastEffectSendOk.value = true
                }
                else -> _lastEffectSendOk.value = false
            }
        }
    }

    fun updateSpeed(v: Int) {
        _mode.value = _mode.value.copy(speed = v)
    }

    /**
     * 速度下发（R19-10.5 拍板）：全灯限幅跟**已选效果**所在分类——我方 tab 为浏览式
     * （不切换效果），厂商"flag 跟浏览 tab"的前提（切 tab 即切效果）在我方 UI 不成立。
     * 厂商事实与决策依据见 ble_protocol.md §2.1 帧#5 注。
     */
    fun sendSpeed() {
        val selectedCategory = (_mode.value.selected - 1) / 17
        fire { lamp.send(LampFrames.speed(_mode.value.speed, selectedCategory == 8)) }
    }

    fun updateSensitivity(v: Int) {
        _mode.value = _mode.value.copy(sensitivity = v)
    }

    fun sendSensitivity() =
        fire { lamp.send(LampFrames.sensitivity(_mode.value.sensitivity)) }

    fun setMicType(value: Int) {
        _mic.update { it.copy(type = value) }
        applyLampPickupFrames()
    }

    fun updateMicSensitivity(v: Int) {
        _mic.update { it.copy(sensitivity = v) }
    }

    fun sendMicSensitivity() = applyLampPickupFrames()

    /**
     * 灯端拾音激活三帧（R20-04）：灯载源 00 + 类型 + 灵敏度——
     * 防灯被旧版/原厂置为手机源后无法由本控制器恢复。声源开关不进 UI（D1）。
     */
    fun applyLampPickupFrames() = fire {
        lamp.send(LampFrames.micSource(false))
        lamp.send(LampFrames.micType(_mic.value.type))
        lamp.send(LampFrames.micSensitivity(_mic.value.sensitivity))
    }

    // ━━━ 定时：槽位编辑/保存/读取（全部 update{} 原子改） ━━━

    /** 草稿编辑：只改本槽并标 dirty（R19-09：编辑后"已读回"文案不得延续） */
    fun updateTimerSlot(openSlot: Boolean, hour: Int, minute: Int, weeks: List<Boolean>) =
        _timer.update {
            it.withSlot(openSlot) { s ->
                if (s.hour == hour && s.minute == minute && s.weeks == weeks) s
                else s.copy(hour = hour, minute = minute, weeks = weeks, dirty = true, revision = s.revision + 1)
            }
        }

    /** 应用到灯（AV04-03 互斥阶段模型）：结果绑定发起时 revision——等待期间的新编辑不被旧结果置 clean */
    fun saveTimerSlot(openSlot: Boolean) {
        val s = _timer.value.slot(openSlot)
        val revAtSave = s.revision
        fire {
            val result = lamp.send(LampFrames.timerSet(s.hour, s.minute, openSlot, weekBits(s.weeks)))
            _timer.update { cur ->
                cur.withSlot(openSlot) { slot ->
                    if (slot.revision != revAtSave) {
                        // 等待期间有新编辑：只记录投递结果，不动 dirty/草稿
                        slot.copy(phase = when (result) {
                            SendResult.SentToAll, SendResult.SentToSome -> TimerPhase.Sent
                            SendResult.NoTarget -> TimerPhase.NoTarget
                            else -> TimerPhase.Failed
                        })
                    } else when (result) {
                        SendResult.SentToAll, SendResult.SentToSome ->
                            slot.copy(dirty = false, phase = TimerPhase.Sent)
                        SendResult.NoTarget ->
                            slot.copy(phase = TimerPhase.NoTarget) // 未连接，草稿已保留
                        else ->
                            slot.copy(phase = TimerPhase.Failed)
                    }
                }
            }
        }
    }

    /**
     * 从灯读取（AV04-03）：busy 防重复；读取目标=首个已连接灯；
     * 空配置清空本槽（关灯槽恢复 23:00 默认）；读取期间的新编辑（revision 变化）丢弃旧观测；
     * 读失败记入 phase（互斥阶段，不遮蔽后续保存成功）。
     */
    fun queryTimerSlot(openSlot: Boolean) {
        if (_timer.value.slot(openSlot).busy) return
        val target = connectedLampName()
        val revAtRequest = _timer.value.slot(openSlot).revision
        viewModelScope.launch {
            _timer.update { it.withSlot(openSlot) { s -> s.copy(busy = true) } }
            val info = runCatching { lamp.queryTimer(openSlot) }.getOrNull()
                ?.let { LampFrames.parseTimerRead(it) }
            _timer.update { cur ->
                cur.withSlot(openSlot) { s ->
                    when {
                        s.revision != revAtRequest -> s.copy(busy = false) // 读取期间有新编辑：丢弃旧观测
                        info == null -> s.copy(busy = false, phase = TimerPhase.Failed)
                        !info.isSet ->
                            if (openSlot) TimerSlot(phase = TimerPhase.Idle)
                            else TimerSlot(hour = 23, minute = 0, phase = TimerPhase.Idle) // 关灯槽保留 23 点默认
                        else -> TimerSlot(
                            hour = info.hour,
                            minute = info.minute,
                            weeks = List(7) { i -> info.weekEnabled(i) },
                            loaded = true,
                            readFrom = target
                        )
                    }
                }
            }
        }
    }

    /** 进定时页即对时（厂商 timer.initView 同构，P0 审计补强）；帧历时间为 Calendar 现取 */
    fun syncClock() {
        val c = java.util.Calendar.getInstance()
        fire {
            lamp.send(
                LampFrames.timeSync(
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.DAY_OF_WEEK) - 1
                )
            )
        }
    }

    /** 定时读取目标：首个已连接灯（名称缺失退 MAC） */
    private fun connectedLampName(): String? {
        val mac = lamp.sessions.value.filterValues { it == SessionState.CONNECTED }.keys.firstOrNull()
            ?: return null
        return lamp.scanned.value.firstOrNull { it.mac == mac }?.name ?: mac
    }

    private fun TimerUiState.slot(openSlot: Boolean): TimerSlot = if (openSlot) open else close

    private inline fun TimerUiState.withSlot(
        openSlot: Boolean,
        transform: (TimerSlot) -> TimerSlot
    ): TimerUiState = if (openSlot) copy(open = transform(open)) else copy(close = transform(close))

    private fun weekBits(weeks: List<Boolean>): Int {
        var b = 0
        weeks.forEachIndexed { i, on -> if (on) b = b or (1 shl i) }
        return b
    }

    // ━━━ 会话就绪初始化（AV04-01/08）━━━
    // 必须放在全部业务属性声明之后：Kotlin 按声明序初始化，此 init 启动的收集器可能
    // 同步读到 _mic（构造期已连接 + 同步 send 返回路径）。此前置版本是实机 NPE 根因。
    init {
        viewModelScope.launch {
            lamp.sessionReady.collect { /* mac -> */ applyLampPickupFrames() }
        }
    }

    companion object {
        fun factory(app: QuartzLampApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(app.lampConnection) as T
        }
    }
}
