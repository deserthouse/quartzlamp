package io.github.deserthouse.quartzlamp.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 手机麦拾音引擎（厂商 VoiceDBModule 行为复刻）：AudioRecord 采 PCM → RMS 分贝 → 归一化响度回调。
 * 生命周期契约（R19-05 重写）：
 * - start/stop 同一所有者；stop()=cancel job，协程在 finally 里停止并 release AudioRecord；
 * - Thread.sleep 换 delay（可取消）；初始化失败走 onError 而非静默；
 * - 回调在 IO 线程执行（onLevel 写 Compose 状态/发帧均线程安全；不依赖主线程）。
 * 与厂商差异：AudioRecord 直采替代 MediaRecorder.getMaxAmplitude（更平滑），帧仍走协议帧#6。
 */
class MicEngine {

    private var job: Job? = null

    /** 录音初始化失败回调（权限被撤/设备占用等） */
    fun start(
        scope: CoroutineScope,
        onLevel: (normalized: Float) -> Unit,
        onError: (() -> Unit)? = null
    ) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            @SuppressLint("MissingPermission") // 调用方已过权限门；失败走 onError
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(2048)
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError?.invoke() }
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                withContext(Dispatchers.Main) { onError?.invoke() }
                return@launch
            }
            try {
                rec.startRecording()
                val buf = ShortArray(1024)
                // 响度→色相映射基准（安静≈蓝紫 265°，最响≈红 0°/360°），70dB 动态窗
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = buf[i] / 32768.0
                        sum += v * v
                    }
                    val rms = sqrt(sum / n)
                    val db = 20 * log10(rms.coerceAtLeast(1e-5))
                    val normalized = ((db + 70.0) / 70.0).coerceIn(0.0, 1.0).toFloat()
                    onLevel(normalized)
                    delay(60) // ≈16fps：律动足够，且可被取消
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 读取异常（录音中途被系统夺走等）：静默退出，资源在 finally 释放
            } finally {
                try {
                    rec.stop()
                } catch (_: IllegalStateException) {
                }
                rec.release()
            }
        }
    }

    fun stop() {
        job?.cancel() // 取消触发协程 finally：stop+release AudioRecord
        job = null
    }
}
