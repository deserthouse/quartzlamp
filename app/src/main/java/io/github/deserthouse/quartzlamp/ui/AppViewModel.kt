package io.github.deserthouse.quartzlamp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.deserthouse.quartzlamp.QuartzLampApp
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.LampConnection
import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import io.github.deserthouse.quartzlamp.ble.SessionState
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 律动声源（2026-10-19 R19-01：切换动作收敛到 VM，帧副作用可单测） */
enum class RhythmSource(val label: String) {
    PHONE_MIC("手机麦"), LAMP_MIC("灯载麦"), MUSIC("本地音乐")
}

/**
 * 全局唯一 ViewModel：连接状态 + 各页控制语义（帧构造全走 protocol 层）。
 * 厂商默认值（ble_protocol.md §4）：speed=50, sensitivity=50。
 */
class AppViewModel(private val lamp: LampConnection) : ViewModel() {

    val state: StateFlow<ConnState> = lamp.state
    val scanned: StateFlow<List<ScannedLamp>> = lamp.scanned
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
        val phoneMic: Boolean = true,
        val type: Int = 1,
        val sensitivity: Int = 50
    )

    private val _mic = MutableStateFlow(MicState())
    val mic: StateFlow<MicState> = _mic

    // ━━━ 律动源（R19-01：源切换驱动灯端帧——灯载麦=00+重下发类型/灵敏度，手机驱动源=01）━━━
    private val _rhythmSource = MutableStateFlow(RhythmSource.PHONE_MIC)
    val rhythmSource: StateFlow<RhythmSource> = _rhythmSource

    fun selectRhythmSource(s: RhythmSource) {
        if (_rhythmSource.value == s) return
        _rhythmSource.value = s
        // 手机麦/本地音乐都是手机驱动 BC09 → 灯端声源须为 01；灯载麦=00 并重下发灯端配置
        lamp.send(LampFrames.micSource(s != RhythmSource.LAMP_MIC))
        if (s == RhythmSource.LAMP_MIC) {
            lamp.send(LampFrames.micType(_mic.value.type))
            lamp.send(LampFrames.micSensitivity(_mic.value.sensitivity))
        }
    }

    // ━━━ 定时（F05/F06 重做）━━━
    /**
     * 单时段槽位：草稿即状态（VM 独立持有，两槽互不干扰——读开灯不丢关灯草稿）。
     * loaded=灯端读到过有效配置；busy=查询中；error=最近一次读取失败。
     * 无"启用"开关：协议无此位（F05），保存即下发、删除=清空重存、读回空=未设置。
     */
    data class TimerSlot(
        val hour: Int = 7,
        val minute: Int = 0,
        val weeks: List<Boolean> = List(7) { false },
        val loaded: Boolean = false,
        val busy: Boolean = false,
        val error: Boolean = false
    )

    data class TimerUiState(
        val open: TimerSlot = TimerSlot(),
        val close: TimerSlot = TimerSlot(hour = 23, minute = 0)
    )

    private val _timer = MutableStateFlow(TimerUiState())
    val timer: StateFlow<TimerUiState> = _timer

    fun setPower(on: Boolean) {
        _powerOn.value = on
        lamp.send(LampFrames.power(on))
    }

    fun setColor(hue: Int, sat: Float, send: Boolean = true) {
        _color.value = _color.value.copy(hue = hue, sat = sat)
        if (send) lamp.send(LampFrames.colorStatic(hue, sat))
    }

    fun updateBrightness(v: Int) {
        // 拖动中只更新本地状态；sendBrightness() 松手时发一帧（防写队列洪泛，对齐厂商行为）
        _color.value = _color.value.copy(bright = v)
    }

    fun sendBrightness() = lamp.send(LampFrames.brightness(_color.value.bright))

    fun selectEffect(tab: Int, position: Int, deviceIndex: Int) {
        _mode.value = _mode.value.copy(tab = tab, selected = deviceIndex)
        lamp.send(LampFrames.modeSelect(deviceIndex))
    }

    fun updateSpeed(v: Int) {
        _mode.value = _mode.value.copy(speed = v)
    }

    fun sendSpeed(quandeng: Boolean) =
        lamp.send(LampFrames.speed(_mode.value.speed, quandeng))

    fun updateSensitivity(v: Int) {
        _mode.value = _mode.value.copy(sensitivity = v)
    }

    fun sendSensitivity() = lamp.send(LampFrames.sensitivity(_mode.value.sensitivity))

    fun setMicSource(phoneMic: Boolean) {
        _mic.value = _mic.value.copy(phoneMic = phoneMic)
        lamp.send(LampFrames.micSource(phoneMic))
    }

    fun setMicType(type: Int) {
        _mic.value = _mic.value.copy(type = type)
        lamp.send(LampFrames.micType(type))
    }

    fun updateMicSensitivity(v: Int) {
        _mic.value = _mic.value.copy(sensitivity = v)
    }

    fun sendMicSensitivity() =
        lamp.send(LampFrames.micSensitivity(_mic.value.sensitivity))

    /** 手机麦 dB → 律动变色（帧#6） */
    fun sendReactive(hue: Int, sat: Float) = lamp.send(LampFrames.reactiveColor(hue, sat))

    // ━━━ 定时：槽位编辑/保存/读取（全部 update{} 原子改） ━━━

    /** 草稿编辑：只改本槽，不动另一槽 */
    fun updateTimerSlot(open: Boolean, hour: Int, minute: Int, weeks: List<Boolean>) =
        _timer.update { it.withSlot(open) { s -> s.copy(hour = hour, minute = minute, weeks = weeks) } }

    /** 应用到灯：保存即下发 timerSet（协议无启用位，F05） */
    fun saveTimerSlot(open: Boolean) {
        val s = _timer.value.slot(open)
        lamp.send(LampFrames.timerSet(s.hour, s.minute, open, weekBits(s.weeks)))
    }

    /** 从灯读取：空配置清空本槽（未设置语义，F06）；失败置 error，不清草稿 */
    fun queryTimerSlot(open: Boolean) {
        viewModelScope.launch {
            _timer.update { it.withSlot(open) { s -> s.copy(busy = true, error = false) } }
            val info = runCatching { lamp.queryTimer(open) }.getOrNull()
                ?.let { LampFrames.parseTimerRead(it) }
            _timer.update { cur ->
                cur.withSlot(open) { s ->
                    when {
                        info == null -> s.copy(busy = false, error = true)
                        !info.isSet -> TimerSlot() // 未设置 → 清空（hour 回默认草稿值，loaded=false）
                        else -> TimerSlot(
                            hour = info.hour,
                            minute = info.minute,
                            weeks = List(7) { i -> info.weekEnabled(i) },
                            loaded = true
                        )
                    }
                }
            }
        }
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

    companion object {
        fun factory(app: QuartzLampApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(app.lampConnection) as T
        }
    }
}
