package io.github.deserthouse.quartzlamp

import android.app.Application
import io.github.deserthouse.quartzlamp.ble.FakeLampConnection
import io.github.deserthouse.quartzlamp.ble.FastBleLampConnection
import io.github.deserthouse.quartzlamp.ble.LampConnection

/** debug 变体：模拟器自动切假灯（可 -PforceRealBle=true 强制真实路径冒烟） */
internal fun createLampConnection(app: Application): LampConnection {
    val isEmulator = android.os.Build.FINGERPRINT.contains("generic")
        || android.os.Build.MODEL.contains("Emulator")
        || android.os.Build.PRODUCT.contains("sdk_gphone")
    return if (isEmulator && !BuildConfig.FORCE_REAL_BLE) {
        FakeLampConnection()
    } else {
        FastBleLampConnection(app).also { it.init() }
    }
}
