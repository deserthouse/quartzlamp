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

    /** 帧#5 速度（byte[2]=01，mode$1:0050-0056 实证）；
     *  quandengMode（当前浏览分类=全灯）时上限 70（mode$1:003a-0042 为 min(v,70)）。
     *  flag 按当前 tab 重估是厂商语义：mode$4.onTabSelected 各分支汇合点 01d9 统一重估，见 ble_protocol.md §2.1。 */
    fun speed(v: Int, quandengMode: Boolean = false): ByteArray {
        require(v in 0..100) { "speed out of range: $v" }
        return byteArrayOf(
            0xBC.toByte(), 0x08, 0x01,
            (if (quandengMode) v.coerceAtMost(70) else v).toByte(),
            0x55
        )
    }

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

    /** 帧#7 灵敏度（模式页；byte[2]=01，mode$2:003e-0044 实证） */
    fun sensitivity(v: Int): ByteArray {
        require(v in 0..100) { "sensitivity out of range: $v" }
        return bc5(0x10, 0x01, v)
    }

    /** 帧#8 拾音源：手机麦=01、灯载麦=00（mic$1 array@0x5e=BC0F0101↔rb_mic_phone=0x7F09014F、
     *  array@0x66=BC0F0100↔rb_mic_device=0x7F09014E，aapt2 资源表实证） */
    fun micSource(phoneMic: Boolean): ByteArray =
        bc5(0x0F, 0x01, if (phoneMic) 0x01 else 0x00)

    /** 帧#9 拾音类型：经典=1、柔和=2、动感=3、迪斯科=4
     *  （mic$2 开关表 6 目标 + array 载荷 + aapt2 资源 id 实证；UI 层按下表值发，不按序号） */
    fun micType(type: Int): ByteArray {
        require(type in 1..4)
        return bc5(0x11, 0x01, type)
    }

    /** 帧#10 拾音灵敏度（byte[2]=01，mic$3:0017-001b 实证） */
    fun micSensitivity(v: Int): ByteArray {
        require(v in 0..100) { "mic sensitivity out of range: $v" }
        return bc5(0x12, 0x01, v)
    }

    // ━━━ 7E 族（头 0x7E 尾 0xEF，9 字节）━━━

    /** 帧#11 RGB 直发（麦克风 RGB 模式） */
    fun rgbDirect(r: Int, g: Int, b: Int): ByteArray = byteArrayOf(
        0x7E, 0x07, 0x05, 0x03,
        r.toByte(), g.toByte(), b.toByte(),
        0, 0xEF.toByte()
    )

    /** 帧#12 定时设置（hour 24 小时制；open=true 开灯定时；weekBits bit0=周一） */
    fun timerSet(hour: Int, minute: Int, open: Boolean, weekBits: Int): ByteArray {
        require(hour in 0..23 && minute in 0..59) { "time out of range: $hour:$minute" }
        return byteArrayOf(
        0x7E, 0x08, 0x82.toByte(),
        hour.toByte(), minute.toByte(), 0,
        if (open) 0 else 1,
        weekBits.toByte(),
        0xEF.toByte()
    )
    }

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

    /** §3 定时读回解析：[3]=时（-1=未设置，timer$8:0047-005b 判 -1 即清 UI 返回）、[4]=分、[7]=周掩码。
     *  收紧口径：长度恰 9 + 设定时数值域校验。不校验帧头尾——厂商不校验、响应包络未实证，
     *  加校验有误丢有效读回的风险（真机验收项，见 ble_protocol.md §5）。 */
    fun parseTimerRead(data: ByteArray): TimerInfo? {
        if (data.size != 9) return null
        val hour = data[3].toInt()
        val minute = data[4].toInt()
        val weekBits = data[7].toInt()
        if (hour == -1) return TimerInfo(hour = -1, minute = minute, weekBits = weekBits)
        if (hour !in 0..23 || minute !in 0..59 || weekBits !in 0..127) return null
        return TimerInfo(hour, minute, weekBits)
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
