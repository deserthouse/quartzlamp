package io.github.deserthouse.quartzlamp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Visualizer 8 位无符号 PCM 归一化（审查 F04 反例：有符号误读使静音变最响） */
class WaveformLevelTest {

    private fun wave(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    @Test fun `静音0x80必须是最小响度`() {
        // T7 反例：旧实现 (b-128)/128 对 0x80 得 -2.0 → 最响；正确值=0
        assertEquals(0.0, WaveformLevel.signedSample(0x80.toByte()), 1e-9)
        assertEquals(0f, WaveformLevel.level(wave(0x80, 0x80, 0x80, 0x80)))
    }

    @Test fun `端点符号正确`() {
        assertEquals(-1.0, WaveformLevel.signedSample(0x00), 1e-9)  // 负峰
        // 0xFF → 127/128：8 位无符号格式下正峰略小于 1（格式事实）
        assertEquals(0.9921875, WaveformLevel.signedSample(0xFF.toByte()), 1e-9) // 正峰
        assertEquals(0.0, WaveformLevel.signedSample(0x80.toByte()), 1e-9) // 零点
    }

    @Test fun `满幅方波电平近满`() {
        // 满幅方波 0x00/0xFF 交替 → rms≈0.992 → level>0.99
        val l = WaveformLevel.level(wave(0x00, 0xFF, 0x00, 0xFF))
        assertTrue("actual=$l", l > 0.99f)
    }

    @Test fun `半幅弱于满幅`() {
        val loud = WaveformLevel.level(wave(0x00, 0xFF, 0x00, 0xFF))
        val soft = WaveformLevel.level(wave(0x40, 0xC0, 0x40, 0xC0)) // ±64
        assertTrue(soft < loud)
        assertTrue(soft > 0f)
    }

    @Test fun `空波形安全`() {
        assertEquals(0.0, WaveformLevel.rms(ByteArray(0)), 1e-9)
        assertEquals(0f, WaveformLevel.level(ByteArray(0)))
    }
}
