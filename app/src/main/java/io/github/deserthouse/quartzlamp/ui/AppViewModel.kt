package io.github.deserthouse.quartzlamp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.deserthouse.quartzlamp.QuartzLampApp
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.LampConnection
import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局唯一 ViewModel：连接状态 + 各页控制语义（帧构造全走 protocol 层）。
 * 厂商默认值（ble_protocol.md §4）：speed=50, sensitivity=50。
 */
class AppViewModel(private val lamp: LampConnection) : ViewModel() {

    val state: StateFlow<ConnState> = lamp.state
    val scanned: StateFlow<List<ScannedLamp>> = lamp.scanned
    val frameLog get() = lamp.frameLog

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

    // ━━━ 定时 ━━━
    data class TimerUiState(
        val openHour: Int = 7,
        val openMinute: Int = 0,
        val openEnabled: Boolean = false,
        val openWeeks: List<Boolean> = List(7) { false },
        val closeHour: Int = 23,
        val closeMinute: Int = 0,
        val closeEnabled: Boolean = false,
        val closeWeeks: List<Boolean> = List(7) { false }
    )

    private val _timer = MutableStateFlow(TimerUiState())
    val timer: StateFlow<TimerUiState> = _timer

    fun startScan() = lamp.startScan()
    fun stopScan() = lamp.stopScan()
    fun connect(l: ScannedLamp) = lamp.connect(l)
    fun disconnect() = lamp.disconnect()

    fun setPower(on: Boolean) {
        _powerOn.value = on
        lamp.send(LampFrames.power(on))
    }

    fun setColor(hue: Int, sat: Float, send: Boolean = true) {
        _color.value = _color.value.copy(hue = hue, sat = sat)
        if (send) lamp.send(LampFrames.colorStatic(hue, sat))
    }

    fun setBrightness(v: Int) {
        _color.value = _color.value.copy(bright = v)
        lamp.send(LampFrames.brightness(v))
    }

    fun selectEffect(tab: Int, position: Int, deviceIndex: Int) {
        _mode.value = _mode.value.copy(tab = tab, selected = deviceIndex)
        lamp.send(LampFrames.modeSelect(deviceIndex))
    }

    fun setSpeed(v: Int, quandeng: Boolean) {
        _mode.value = _mode.value.copy(speed = v)
        lamp.send(LampFrames.speed(v, quandeng))
    }

    fun setSensitivity(v: Int) {
        _mode.value = _mode.value.copy(sensitivity = v)
        lamp.send(LampFrames.sensitivity(v))
    }

    fun setMicSource(phoneMic: Boolean) {
        _mic.value = _mic.value.copy(phoneMic = phoneMic)
        lamp.send(LampFrames.micSource(phoneMic))
    }

    fun setMicType(type: Int) {
        _mic.value = _mic.value.copy(type = type)
        lamp.send(LampFrames.micType(type))
    }

    fun setMicSensitivity(v: Int) {
        _mic.value = _mic.value.copy(sensitivity = v)
        lamp.send(LampFrames.micSensitivity(v))
    }

    /** 手机麦 dB → 律动变色（帧#6） */
    fun sendReactive(hue: Int, sat: Float) = lamp.send(LampFrames.reactiveColor(hue, sat))

    fun updateTimerOpen(hour: Int, minute: Int, enabled: Boolean, weeks: List<Boolean>) {
        _timer.value = _timer.value.copy(
            openHour = hour, openMinute = minute, openEnabled = enabled, openWeeks = weeks
        )
        lamp.send(LampFrames.timerSet(hour, minute, open = true, weekBits = weekBits(weeks)))
    }

    fun updateTimerClose(hour: Int, minute: Int, enabled: Boolean, weeks: List<Boolean>) {
        _timer.value = _timer.value.copy(
            closeHour = hour, closeMinute = minute, closeEnabled = enabled, closeWeeks = weeks
        )
        lamp.send(LampFrames.timerSet(hour, minute, open = false, weekBits = weekBits(weeks)))
    }

    fun queryTimer(open: Boolean, onResult: (LampFrames.TimerInfo?) -> Unit) =
        lamp.queryTimer(open) { bytes -> onResult(bytes?.let(LampFrames::parseTimerRead)) }

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
