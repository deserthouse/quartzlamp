package io.github.deserthouse.quartzlamp.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Visualizer 波形 → 归一化响度（纯函数，可单测）。
 *
 * Android Visualizer 波形是 **8 位无符号 PCM**（0x80=零点）——
 * 必须先 and 0xFF 再减 128（审查 F04：有符号误读使静音 0x80 映射为 -2.0 → 最响）。
 * 响度窗 50dB：静音→0，rms=1→1（T7 反例入注：静音必须是最暗而非最响）。
 */
object WaveformLevel {

    /** rms → level 的动态窗（dB），与厂商"安静深蓝→高潮红"的映射幅度对齐 */
    const val DYNAMIC_WINDOW_DB = 50.0

    fun signedSample(b: Byte): Double = ((b.toInt() and 0xFF) - 128) / 128.0

    fun rms(waveform: ByteArray): Double {
        if (waveform.isEmpty()) return 0.0
        var sum = 0.0
        for (b in waveform) {
            val s = signedSample(b)
            sum += s * s
        }
        return sqrt(sum / waveform.size)
    }

    /** 波形 → [0,1] 响度；静音波形（全 0x80）必须得 0 */
    fun level(waveform: ByteArray): Float {
        val r = rms(waveform)
        val db = 20 * log10(r.coerceAtLeast(1e-5))
        return ((db + DYNAMIC_WINDOW_DB) / DYNAMIC_WINDOW_DB).coerceIn(0.0, 1.0).toFloat()
    }
}
