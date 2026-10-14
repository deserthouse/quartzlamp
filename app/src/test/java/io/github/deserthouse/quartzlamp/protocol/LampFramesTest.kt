package io.github.deserthouse.quartzlamp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧表断言测试 —— 事实源 docs/ble_protocol.md。
 * 期望字节全部来自厂商 APK 反汇编实证（tests/apk_analysis/m0_callers/）。
 */
class LampFramesTest {

    private fun h(vararg ints: Int) = ints.map { it.toByte() }.toByteArray()

    @Test fun `帧1 电源开`() = assertArrayEquals(h(0xBC, 0x01, 0x01, 0x01, 0x55), LampFrames.power(true))

    @Test fun `帧1 电源关`() = assertArrayEquals(h(0xBC, 0x01, 0x01, 0x00, 0x55), LampFrames.power(false))

    @Test fun `帧2 静态调色 HSV大端16位除256`() {
        // H=300, S=0.8 → S×1000=800=0x320 → BC 04 06 01 2C 03 20 00 00 55
        assertArrayEquals(
            h(0xBC, 0x04, 0x06, 0x01, 0x2C, 0x03, 0x20, 0x00, 0x00, 0x55),
            LampFrames.colorStatic(300, 0.8f)
        )
        // H=60, S=1.0 → 1000=0x3E8 → 00 3C 03 E8
        assertArrayEquals(
            h(0xBC, 0x04, 0x06, 0x00, 0x3C, 0x03, 0xE8, 0x00, 0x00, 0x55),
            LampFrames.colorStatic(60, 1f)
        )
    }

    @Test fun `帧3 亮度 除255 且下限3`() {
        assertArrayEquals(
            h(0xBC, 0x05, 0x06, 0x00, 0x64, 0, 0, 0, 0, 0x55),
            LampFrames.brightness(100)
        )
        // 下限 3（厂商 color$2 实证）
        assertArrayEquals(
            h(0xBC, 0x05, 0x06, 0x00, 0x03, 0, 0, 0, 0, 0x55),
            LampFrames.brightness(0)
        )
    }

    @Test fun `帧4 模式选择 除255`() = assertArrayEquals(
        h(0xBC, 0x06, 0x02, 0x00, 0x2A, 0x55),
        LampFrames.modeSelect(42)
    )

    @Test fun `帧5 速度与全灯下限70`() {
        assertArrayEquals(h(0xBC, 0x08, 0x02, 0x32, 0x55), LampFrames.speed(50))
        assertArrayEquals(h(0xBC, 0x08, 0x02, 0x46, 0x55), LampFrames.speed(10, quandengMode = true))
    }

    @Test fun `帧6 律动变色 除255`() = assertArrayEquals(
        // H=240, S=0.5 → 500=0x1F4 → /255=1, %255=245
        h(0xBC, 0x09, 0x06, 0x00, 0xF0, 0x01, 0xF5, 0x00, 0x00, 0x55),
        LampFrames.reactiveColor(240, 0.5f)
    )

    @Test fun `帧7 灵敏度`() = assertArrayEquals(h(0xBC, 0x10, 0x02, 0x32, 0x55), LampFrames.sensitivity(50))

    @Test fun `帧8 拾音源`() {
        assertArrayEquals(h(0xBC, 0x0F, 0x01, 0x00, 0x55), LampFrames.micSource(phoneMic = true))
        assertArrayEquals(h(0xBC, 0x0F, 0x01, 0x01, 0x55), LampFrames.micSource(phoneMic = false))
    }

    @Test fun `帧9 拾音类型1到4`() {
        assertArrayEquals(h(0xBC, 0x11, 0x01, 0x01, 0x55), LampFrames.micType(1))
        assertArrayEquals(h(0xBC, 0x11, 0x01, 0x04, 0x55), LampFrames.micType(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `帧9 类型越界拒绝`() { LampFrames.micType(5) }

    @Test fun `帧10 拾音灵敏度`() = assertArrayEquals(h(0xBC, 0x12, 0x02, 0x19, 0x55), LampFrames.micSensitivity(25))

    @Test fun `帧11 RGB直发`() = assertArrayEquals(
        h(0x7E, 0x07, 0x05, 0x03, 0xFF, 0x80, 0x00, 0x00, 0xEF),
        LampFrames.rgbDirect(255, 128, 0)
    )

    @Test fun `帧12 定时设置 开灯且周位`() = assertArrayEquals(
        // 7:30 开灯，周一+周三+周五 = 0b0010101 = 0x15
        h(0x7E, 0x08, 0x82, 0x07, 0x1E, 0x00, 0x00, 0x15, 0xEF),
        LampFrames.timerSet(7, 30, open = true, weekBits = 0b0010101)
    )

    @Test fun `帧12 定时设置 关灯选择子`() = assertArrayEquals(
        h(0x7E, 0x08, 0x82, 0x17, 0x00, 0x00, 0x01, 0x40, 0xEF),
        LampFrames.timerSet(23, 0, open = false, weekBits = 0x40)
    )

    @Test fun `帧13 定时查询`() {
        assertArrayEquals(
            h(0x7E, 0x08, 0x82, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0xEF),
            LampFrames.timerQuery(open = true)
        )
        assertArrayEquals(
            h(0x7E, 0x08, 0x82, 0xFF, 0xFF, 0xFF, 0x01, 0x00, 0xEF),
            LampFrames.timerQuery(open = false)
        )
    }

    @Test fun `帧14 时间同步`() = assertArrayEquals(
        // 12:34:56 周三（Sun=0 → Wed=3）
        h(0x7E, 0x07, 0x83, 0x0C, 0x22, 0x38, 0x03, 0xFF, 0xEF),
        LampFrames.timeSync(12, 34, 56, 3)
    )

    @Test fun `定时读回解析`() {
        val info = LampFrames.parseTimerRead(
            h(0x7E, 0x08, 0x82, 0x07, 0x1E, 0x00, 0x00, 0x15, 0xEF)
        )!!
        assertEquals(7, info.hour)
        assertEquals(30, info.minute)
        assertTrue(info.isSet)
        assertTrue(info.weekEnabled(0))  // 周一
        assertFalse(info.weekEnabled(1)) // 周二
        assertTrue(info.weekEnabled(2))  // 周三
        // 未设置：时=0xFF=-1
        val unset = LampFrames.parseTimerRead(
            h(0x7E, 0x08, 0x82, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0xEF)
        )!!
        assertFalse(unset.isSet)
    }

    @Test fun `短包拒绝解析`() = assertNull(LampFrames.parseTimerRead(h(0x7E, 0x08)))

    @Test fun `hex 日志格式与原厂对齐`() =
        assertEquals("BC01010155", LampFrames.hex(LampFrames.power(true)))
}
