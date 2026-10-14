package io.github.deserthouse.quartzlamp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.deserthouse.quartzlamp.ui.AppViewModel
import kotlin.math.log10
import kotlin.math.sqrt

data class Song(val title: String, val artist: String, val uri: Uri, val id: Long)

/**
 * 音乐页（厂商行为同构）：MediaStore 选曲 → MediaPlayer 播放 →
 * Visualizer 抓波形 RMS → 响度映射色相 → 帧律动变色（协议帧#6）。
 */
@Composable
fun MusicScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf(listOf<Song>()) }
    var current by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED // Visualizer 需要
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map -> hasPermission = map.values.all { it }; if (hasPermission) songs = querySongs(context) }

    fun load() {
        if (hasPermission && songs.isEmpty()) songs = querySongs(context)
    }
    load()

    val player = remember { MediaPlayer() }
    val visualizer = remember { arrayOf<Visualizer?>(null) }
    var reactive by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            visualizer[0]?.release()
            player.release()
        }
    }

    fun startReactive(sessionId: Int) {
        visualizer[0]?.release()
        if (!reactive) return
        val v = Visualizer(sessionId).apply {
            captureSize = Visualizer.getCaptureSizeRange()[0]
            // rate 单位 mHz：max/2 = 10Hz 回调（此前误用 captureSize/2=64mHz，~15s 才回调一次）
            setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(viz: Visualizer, waveform: ByteArray, samplingRate: Int) {
                        var sum = 0.0
                        for (b in waveform) {
                            val s = (b - 128) / 128.0
                            sum += s * s
                        }
                        val rms = sqrt(sum / waveform.size)
                        val db = 20 * log10(rms.coerceAtLeast(1e-5))
                        val level = ((db + 50.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
                        // 响度→色相环：安静深蓝 265° → 高潮红 0°
                        vm.sendReactive(((1f - level) * 265f).toInt(), 1f)
                    }
                    override fun onFftDataCapture(viz: Visualizer, fft: ByteArray, samplingRate: Int) {}
                },
                Visualizer.getMaxCaptureRate() / 2, true, false
            )
            enabled = true
        }
        visualizer[0] = v
    }

    fun play(song: Song) {
        try {
            player.reset()
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            player.setDataSource(context, song.uri)
            player.prepare()
            player.start()
            playing = true
            // Visualizer 失败不影响播放本身（部分设备 HAL 不支持效果捕获）
            try {
                startReactive(player.audioSessionId)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
            playing = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("音乐律动", style = MaterialTheme.typography.titleLarge)

        if (!hasPermission) {
            Text("需要音频读取权限浏览本地曲目", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Button(onClick = {
                permLauncher.launch(
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO)
                )
            }) {
                Text("授予权限")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(current?.title ?: "未播放", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(current?.artist ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                IconButton(onClick = {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        current?.let { s ->
                            player.start()
                            startReactive(player.audioSessionId)
                            playing = true
                        }
                    }
                }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "播放/暂停")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("律动同步", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(
                    checked = reactive,
                    onCheckedChange = {
                        reactive = it
                        if (it && playing) startReactive(player.audioSessionId)
                        else visualizer[0]?.enabled = it && playing
                    }
                )
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(songs) { song ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            song.title,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current?.id == song.id) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        FilledIconButton(onClick = { current = song; play(song) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun querySongs(context: android.content.Context): List<Song> {
    val out = ArrayList<Song>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST
    )
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection, null, null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            out.add(
                Song(
                    c.getString(titleCol) ?: "?",
                    c.getString(artistCol) ?: "",
                    Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    id
                )
            )
        }
    }
    return out
}
