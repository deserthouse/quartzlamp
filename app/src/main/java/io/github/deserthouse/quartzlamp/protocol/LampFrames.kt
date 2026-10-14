package io.github.deserthouse.quartzlamp.protocol

/**
 * 拾音灯帧构造器 —— 唯一事实源 docs/ble_protocol.md 的代码化。
 * 每个函数与帧表条目一一对应；改帧必须同步文档并过 LampFramesTest。
 *
 * 厂商编码怪癖（有意保留）：
 * - 静态调色(帧#2) HSV 大端 16 位按 /256 编码；律动变色(帧#6)与亮度(帧#3)按 /255 编码。
 * - 定时设置周位掩码 bit i = 第 i 天（bit0=周一，待真机验证绝对对应）。
 */
object LampFrames {
    const val SERVICE_UUID = "0000FFF0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000FFF3-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    // ━━━ BC 族（头 0xBC 尾 0x55）━━━

    /** 帧#1 电源开关 */
    fun power(on: Boolean): ByteArray = bc5(0x01, 0x01, if (on) 1 else 0)

    /** 帧#2 静态调色（h∈[0,360) 色相，s∈[0,1] 饱和度）——大端 16 位 /256 */
    fun colorStatic(h: Int, s: Float): ByteArray {
        require(h in 0..359) { "hue out of range: $h" }
        val sv = (s * 1000).toInt().coerceIn(0, 1000)
        return byteArrayOf(
            0xBC.toByte(), 0x04, 0x06,
            (h / 256).toByte(), (h % 256).toByte(),
            (sv / 256).toByte(), (sv % 256).toByte(),
            0, 0, 0x55
        )
    }

    /** 帧#3 亮度（v=明度进度值，厂商下限 3）——大端 16 位 /255 */
    fun brightness(v: Int): ByteArray {
        val vv = v.coerceAtLeast(3)
        return byteArrayOf(
            0xBC.toByte(), 0x05, 0x06,
            (vv / 255).toByte(), (vv % 255).toByte(),
            0, 0, 0, 0, 0x55
        )
    }

    /** 帧#4 灯效模式选择（大端 16 位 /255） */
    fun modeSelect(index: Int): ByteArray =
        byteArrayOf(
            0xBC.toByte(), 0x06, 0x02,
            (index / 255).toByte(), (index % 255).toByte(),
            0x55
        )

    /** 帧#5 速度（全灯模式下限 70） */
    fun speed(v: Int, quandengMode: Boolean = false): ByteArray =
        byteArrayOf(
            0xBC.toByte(), 0x08, 0x02,
            (if (quandengMode) v.coerceAtLeast(70) else v).toByte(),
            0x55
        )

    /** 帧#6 律动变色（麦/音乐实时 HSV）——大端 16 位 /255 */
    fun reactiveColor(h: Int, s: Float): ByteArray {
        val sv = (s * 1000).toInt().coerceIn(0, 1000)
        return byteArrayOf(
            0xBC.toByte(), 0x09, 0x06,
            (h / 255).toByte(), (h % 255).toByte(),
            (sv / 255).toByte(), (sv % 255).toByte(),
            0, 0, 0x55
        )
    }

    /** 帧#7 灵敏度（模式页） */
    fun sensitivity(v: Int): ByteArray =
        bc5(0x10, 0x02, v)

    /** 帧#8 拾音开关（micOn：true=手机麦侧待验证方向，见 ble_protocol.md §5.1） */
    fun micSource(phoneMic: Boolean): ByteArray =
        bc5(0x0F, 0x01, if (phoneMic) 0x00 else 0x01)

    /** 帧#9 拾音类型（1..4 ↔ 经典/迪斯科/动感/柔和，映射待验证） */
    fun micType(type: Int): ByteArray {
        require(type in 1..4)
        return bc5(0x11, 0x01, type)
    }

    /** 帧#10 拾音灵敏度 */
    fun micSensitivity(v: Int): ByteArray =
        bc5(0x12, 0x02, v)

    // ━━━ 7E 族（头 0x7E 尾 0xEF，9 字节）━━━

    /** 帧#11 RGB 直发（麦克风 RGB 模式） */
    fun rgbDirect(r: Int, g: Int, b: Int): ByteArray = byteArrayOf(
        0x7E, 0x07, 0x05, 0x03,
        r.toByte(), g.toByte(), b.toByte(),
        0, 0xEF.toByte()
    )

    /** 帧#12 定时设置（hour 24 小时制；open=true 开灯定时；weekBits bit0=周一） */
    fun timerSet(hour: Int, minute: Int, open: Boolean, weekBits: Int): ByteArray = byteArrayOf(
        0x7E, 0x08, 0x82.toByte(),
        hour.toByte(), minute.toByte(), 0,
        if (open) 0 else 1,
        weekBits.toByte(),
        0xEF.toByte()
    )

    /** 帧#13 定时查询（发送后 GATT read 读回） */
    fun timerQuery(open: Boolean): ByteArray = byteArrayOf(
        0x7E, 0x08, 0x82.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        if (open) 0 else 1,
        0,
        0xEF.toByte()
    )

    /** 帧#14 时间同步（weekday：周日=0..周六=6，Calendar.DAY_OF_WEEK-1） */
    fun timeSync(hour: Int, minute: Int, second: Int, weekdaySun0: Int): ByteArray = byteArrayOf(
        0x7E, 0x07, 0x83.toByte(),
        hour.toByte(), minute.toByte(), second.toByte(),
        weekdaySun0.toByte(), 0xFF.toByte(),
        0xEF.toByte()
    )

    /** §3 定时读回解析：Triple(时,-1=未设置, 分, 周位掩码)；格式不符返回 null */
    fun parseTimerRead(data: ByteArray): TimerInfo? {
        if (data.size < 8) return null
        return TimerInfo(
            hour = data[3].toInt(),
            minute = data[4].toInt(),
            weekBits = data[7].toInt()
        )
    }

    data class TimerInfo(val hour: Int, val minute: Int, val weekBits: Int) {
        val isSet: Boolean get() = hour != -1
        fun weekEnabled(dayIndex0Mon: Int): Boolean = (weekBits shr dayIndex0Mon) and 1 == 1
    }

    // ━━━ 工具 ━━━

    private fun bc5(cmd: Int, sub: Int, value: Int) = byteArrayOf(
        0xBC.toByte(), cmd.toByte(), sub.toByte(), value.toByte(), 0x55
    )

    /** 帧十六进制串（BLE-WRITE 日志格式，与原厂 logcat 对齐便于对照验收） */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}
