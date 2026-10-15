package io.github.deserthouse.quartzlamp

import android.app.Application
import io.github.deserthouse.quartzlamp.ble.FastBleLampConnection
import io.github.deserthouse.quartzlamp.ble.FakeLampConnection
import io.github.deserthouse.quartzlamp.ble.LampConnection

class QuartzLampApp : Application() {

    lateinit var lampConnection: LampConnection
        private set

    override fun onCreate() {
        super.onCreate()
        // AVD 连不了 BLE 外设（项目红线#2）——模拟器上自动切假灯实现供全流程自测；
        // debug 可 -PforceRealBle=true 强制真实路径冒烟（F01 初始化验证），release 恒走真实实现
        val isEmulator = android.os.Build.FINGERPRINT.contains("generic")
            || android.os.Build.MODEL.contains("Emulator")
            || android.os.Build.PRODUCT.contains("sdk_gphone")
        lampConnection = if (isEmulator && !BuildConfig.FORCE_REAL_BLE) {
            FakeLampConnection()
        } else {
            FastBleLampConnection(this).also { it.init() }
        }
    }
}
