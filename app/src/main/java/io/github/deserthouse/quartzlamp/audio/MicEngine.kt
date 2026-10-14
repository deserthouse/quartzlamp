package io.github.deserthouse.quartzlamp.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 手机麦拾音引擎（厂商 VoiceDBModule 行为复刻）：
 * AudioRecord 采 PCM → RMS 分贝 → 归一化响度 → 回调 hue（响度映射色相环）。
 * 与厂商差异：用 AudioRecord 直采替代 MediaRecorder.getMaxAmplitude（更平滑，帧率更高），
 * 下发帧仍是协议帧#6（BC 09 律动变色），设备侧行为不变。
 */
class MicEngine {

    private var job: Job? = null
    private var record: AudioRecord? = null

    fun start(scope: CoroutineScope, onLevel: (normalized: Float) -> Unit) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @SuppressLint("MissingPermission") // 调用方已过权限门
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, 44100,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(2048)
            )
            record = rec
            rec.startRecording()
            val buf = ShortArray(1024)
            // 响度→色相映射基准（安静≈蓝紫 265°，最响≈红 0°/360°）
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                val rms = sqrt(sum / n)
                val db = 20 * log10(rms.coerceAtLeast(1e-5)) // ≈ -100..0
                val normalized = ((db + 70.0) / 70.0).coerceIn(0.0, 1.0).toFloat() // 70dB 动态窗
                onLevel(normalized)
                Thread.sleep(60) // ≈16fps，律动足够且不塞写队列
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        record = null
    }
}
