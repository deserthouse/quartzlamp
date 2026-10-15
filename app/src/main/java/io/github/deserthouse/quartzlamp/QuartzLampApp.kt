package io.github.deserthouse.quartzlamp

import android.app.Application
import io.github.deserthouse.quartzlamp.ble.LampConnection

class QuartzLampApp : Application() {

    lateinit var lampConnection: LampConnection
        private set

    override fun onCreate() {
        super.onCreate()
        // 实现选择在变体级 LampProvider（debug=模拟器假灯/真实现可切；release=恒真实现）
        lampConnection = createLampConnection(this)
    }
}
