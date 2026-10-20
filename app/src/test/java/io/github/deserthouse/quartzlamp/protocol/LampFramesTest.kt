package io.github.deserthouse.quartzlamp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧表断言测试 —— 事实源 docs/ble_protocol.md。
 *
 * 独立口径纪律（2026-10-15 R0 起）：期望字节必须抄自厂商反汇编档案
 * tests/apk_analysis/m0_callers/ 的原文（含方法名+指令偏移），禁止从
 * LampFrames 实现反算——单测与实现共享同一错误前提时会互证成假绿（20/20 假绿事故）。
 */
class LampFramesTest {

    private fun h(vararg ints: Int) = ints.map { it.toByte() }.toByteArray()

    // CR-7：防御对称性——越界 require，边界值正常成帧
    @Test(expected = IllegalArgumentException::class) fun `速度越界拒绝`() { LampFrames.speed(101) }
    @Test(expected = IllegalArgumentException::class) fun `速度负值拒绝`() { LampFrames.speed(-1) }
    @Test(expected = IllegalArgumentException::class) fun `灵敏度越界拒绝`() { LampFrames.sensitivity(101) }
    @Test(expected = IllegalArgumentException::class) fun `拾音灵敏度负值拒绝`() { LampFrames.micSensitivity(-1) }
    @Test(expected = IllegalArgumentException::class) fun `定时小时越界拒绝`() { LampFrames.timerSet(24, 0, true, 0) }
    @Test(expected = IllegalArgumentException::class) fun `定时分钟越界拒绝`() { LampFrames.timerSet(7, 60, true, 0) }

    @Test fun `域校验_边界通过`() {
        LampFrames.speed(0); LampFrames.speed(100)
        LampFrames.sensitivity(0); LampFrames.sensitivity(100)
        LampFrames.micSensitivity(0); LampFrames.micSensitivity(100)
        LampFrames.timerSet(0, 0, true, 0); LampFrames.timerSet(23, 59, false, 0x7F)
    }

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

    @Test fun `帧5 速度 byte2为01`() {
        // mode$1__onStopTrackingTouch.txt 004c-005f：[0]=0xBC, [1]=8(0050-0053), [2]=v3=1(0055-0056), [3]=v5, [4]=0x55
        assertArrayEquals(h(0xBC, 0x08, 0x01, 0x32, 0x55), LampFrames.speed(50))
    }

    @Test fun `帧5 全灯限幅为上限70`() {
        // mode$1:003c-0042：if-le v5,70 跳过；否则 v5=70 —— 即 min(v,70)
        // speed=10：10≤70 不变（对照审查复现表：原厂 BC 08 01 0A 55）
        assertArrayEquals(h(0xBC, 0x08, 0x01, 0x0A, 0x55), LampFrames.speed(10, quandengMode = true))
        // speed=90：压到 70=0x46（对照审查复现表：原厂 BC 08 01 46 55）
        assertArrayEquals(h(0xBC, 0x08, 0x01, 0x46, 0x55), LampFrames.speed(90, quandengMode = true))
        // 非全灯 tab 不限幅（mode$1:003e if-eqz 跳过整段钳制）
        assertArrayEquals(h(0xBC, 0x08, 0x01, 0x5A, 0x55), LampFrames.speed(90))
    }

    @Test fun `帧6 律动变色 除255`() = assertArrayEquals(
        // H=240, S=0.5 → 500=0x1F4 → /255=1, %255=245
        h(0xBC, 0x09, 0x06, 0x00, 0xF0, 0x01, 0xF5, 0x00, 0x00, 0x55),
        LampFrames.reactiveColor(240, 0.5f)
    )

    @Test fun `帧7 灵敏度 byte2为01`() {
        // mode$2__onStopTrackingTouch.txt 003a-0044：[1]=16(0x10), [2]=v3=1
        assertArrayEquals(h(0xBC, 0x10, 0x01, 0x32, 0x55), LampFrames.sensitivity(50))
    }

    @Test fun `帧8 拾音源 手机麦01灯载麦00`() {
        // mic$1__onCheckedChanged.txt array@0x5e: "bc0f 0101 5500" ↔ case rb_mic_phone(0x7F09014F, 分支0x5)
        assertArrayEquals(h(0xBC, 0x0F, 0x01, 0x01, 0x55), LampFrames.micSource(phoneMic = true))
        // array@0x66: "bc0f 0100 5500" ↔ case rb_mic_device(0x7F09014E, 分支0x21)
        assertArrayEquals(h(0xBC, 0x0F, 0x01, 0x00, 0x55), LampFrames.micSource(phoneMic = false))
    }

    @Test fun `帧9 拾音类型 经典1柔和2动感3迪斯科4`() {
        // mic$2__onCheckedChanged.txt 开关表(2f8370: first_key=0x7F09014B)+四条 array 载荷：
        // rb_classic →0x2c→ array@0x62 "bc11 0101"   （1=经典）
        assertArrayEquals(h(0xBC, 0x11, 0x01, 0x01, 0x55), LampFrames.micType(1))
        // rb_soft    →0x04→ array@0x4a "bc11 0102"   （2=柔和）
        assertArrayEquals(h(0xBC, 0x11, 0x01, 0x02, 0x55), LampFrames.micType(2))
        // rb_dynamic →0x11→ array@0x52 "bc11 0103"   （3=动感）
        assertArrayEquals(h(0xBC, 0x11, 0x01, 0x03, 0x55), LampFrames.micType(3))
        // rb_disco   →0x1e→ array@0x5a "bc11 0104"   （4=迪斯科）
        assertArrayEquals(h(0xBC, 0x11, 0x01, 0x04, 0x55), LampFrames.micType(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `帧9 类型越界拒绝`() { LampFrames.micType(5) }

    @Test fun `帧10 拾音灵敏度 byte2为01`() {
        // mic$3__onStopTrackingTouch.txt 0015-0023：[1]=18(0x12), [2]=v3=1
        assertArrayEquals(h(0xBC, 0x12, 0x01, 0x19, 0x55), LampFrames.micSensitivity(25))
    }

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
        // 未设置：时=0xFF=-1（timer$8:0047-005b 判 -1 即返回）
        val unset = LampFrames.parseTimerRead(
            h(0x7E, 0x08, 0x82, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0xEF)
        )!!
        assertFalse(unset.isSet)
    }

    @Test fun `定时读回 长度必须恰9`() {
        // R0 收紧：厂商只读 [3][4][7]，我方收紧为长度恰 9（7E 族定长）——短包长包都拒绝
        assertNull(LampFrames.parseTimerRead(h(0x7E, 0x08)))
        assertNull(LampFrames.parseTimerRead(h(0x7E, 0x08, 0x82, 0x07, 0x1E, 0x00, 0x00, 0x15, 0xEF, 0x00)))
    }

    @Test fun `定时读回 设定时数值域校验`() {
        // 时=24 / 分=60 / 周掩码=0x80 越界 → 拒绝（厂商不校验，我方防垃圾包入 UI）
        assertNull(LampFrames.parseTimerRead(h(0x7E, 0x08, 0x82, 24, 0x1E, 0x00, 0x00, 0x15, 0xEF)))
        assertNull(LampFrames.parseTimerRead(h(0x7E, 0x08, 0x82, 0x07, 60, 0x00, 0x00, 0x15, 0xEF)))
        assertNull(LampFrames.parseTimerRead(h(0x7E, 0x08, 0x82, 0x07, 0x1E, 0x00, 0x00, 0x80, 0xEF)))
    }

    @Test fun `hex 日志格式与原厂对齐`() =
        assertEquals("BC01010155", LampFrames.hex(LampFrames.power(true)))
}
