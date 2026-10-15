package io.github.deserthouse.quartzlamp

import android.app.Application
import io.github.deserthouse.quartzlamp.ble.LampConnection
import kotlinx.coroutines.flow.MutableStateFlow

class QuartzLampApp : Application() {

    lateinit var lampConnection: LampConnection
        private set

    /** 主题偏好：false=石英色板（品牌默认）/ true=系统动态色（审查 §6.2 双轨） */
    val dynamicTheme = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        // 实现选择在变体级 LampProvider（debug=模拟器假灯/真实现可切；release=恒真实现）
        lampConnection = createLampConnection(this)
        dynamicTheme.value = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_DYNAMIC, false)
    }

    fun setDynamicTheme(on: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DYNAMIC, on).apply()
        dynamicTheme.value = on
    }

    private companion object {
        const val PREFS = "theme"
        const val KEY_DYNAMIC = "dynamic"
    }
}
