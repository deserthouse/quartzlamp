package io.github.deserthouse.quartzlamp

import android.app.Application
import io.github.deserthouse.quartzlamp.ble.FastBleLampConnection
import io.github.deserthouse.quartzlamp.ble.LampConnection

/** release 变体：恒真实实现（假灯类物理隔离在 debug sourceSet，不出现在 release 产物，F11） */
internal fun createLampConnection(app: Application): LampConnection =
    FastBleLampConnection(app).also { it.init() }
