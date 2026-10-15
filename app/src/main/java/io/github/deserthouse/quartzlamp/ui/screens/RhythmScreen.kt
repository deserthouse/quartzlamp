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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.deserthouse.quartzlamp.audio.MicEngine
import io.github.deserthouse.quartzlamp.audio.WaveformLevel
import io.github.deserthouse.quartzlamp.ui.AppViewModel

/** 律动屏三源（审查 §6.3：手机麦 / 灯载麦 / 本地音乐） */
private enum class RhythmSource(val label: String) {
    PHONE_MIC("手机麦"), LAMP_MIC("灯载麦"), MUSIC("本地音乐")
}

private data class Song(val title: String, val artist: String, val uri: Uri, val id: Long)

private fun audioPermissionName(): String =
    if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * 律动屏（光谱控制台，麦克风+音乐合并——2026-10-15 拍板）：
 * 三源切换（切源即停其它引擎）；手机麦为显式"开始聆听"（不悄悄录音，审查 §6.3）；
 * 灯端拾音设置（类型/灵敏度）与声源无关，固定在底部卡片。
 */
@Composable
fun RhythmScreen(vm: AppViewModel) {
    var source by rememberSaveable { mutableStateOf(RhythmSource.PHONE_MIC) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("律动", style = MaterialTheme.typography.headlineLarge)

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RhythmSource.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = source == s,
                    onClick = { source = s },
                    shape = SegmentedButtonDefaults.itemShape(i, RhythmSource.entries.size),
                    icon = {}
                ) { Text(s.label) }
            }
        }

        when (source) {
            RhythmSource.PHONE_MIC -> PhoneMicPanel(vm)
            RhythmSource.LAMP_MIC -> LampMicPanel()
            RhythmSource.MUSIC -> MusicPanel(vm)
        }

        // ━━━ 灯端拾音设置（类型/灵敏度——对灯端生效，与声源无关）━━━
        LampTuningCard(vm)
        Spacer(Modifier.height(8.dp))
    }
}

// ━━━ 手机麦：显式聆听开关 + 真实电平条 ━━━

@Composable
private fun PhoneMicPanel(vm: AppViewModel) {
    val context = LocalContext.current
    val mic by vm.mic.collectAsState()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    val engine = remember { MicEngine() }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) { onDispose { engine.stop() } }

    // 聆听中启动采音；面板离开组合时 onDispose 停引擎（切源/离屏都覆盖）
    DisposableEffect(listening, hasPermission) {
        if (listening && hasPermission) {
            vm.setMicSource(true)
            engine.start(kotlinx.coroutines.MainScope()) { l ->
                level = l
                vm.sendReactive(((1f - l) * 265f).toInt(), 1f)
            }
        } else {
            engine.stop()
            level = 0f
            if (listening && !hasPermission) listening = false
        }
        onDispose { engine.stop() }
    }

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手机麦克风律动", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (listening) "聆听中" else "已停止",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (listening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 大主操作：开始聆听 / 停止（≥48dp 触区）
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = if (listening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (listening) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else listening = !listening
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Mic, contentDescription = if (listening) "停止聆听" else "开始聆听",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            // 真实电平条（采音时活动；停止/无权限时静止为空——审查 §6.4）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(level)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Text(
                if (!hasPermission) "需要麦克风权限驱动律动" else "响度映射色相：安静深蓝 → 高潮红",
                style = MaterialTheme.typography.labelMedium,
                color = if (!hasPermission) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ━━━ 灯载麦：灯自带麦克风接管 ━━━

@Composable
private fun LampMicPanel() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.SurroundSound, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("灯载麦克风", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "灯自带麦克风接管拾音：对灯说话或放音乐即可律动。" +
                    "灵敏度与拾音类型在下方\"灯端拾音设置\"调整。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ━━━ 本地音乐：曲库 + 播放 + 律动开关 ━━━

@Composable
private fun MusicPanel(vm: AppViewModel) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf(listOf<Song>()) }
    var current by remember { mutableStateOf<Song?>(null) }
    var playing by remember { mutableStateOf(false) }
    var reactive by remember { mutableStateOf(true) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermissionName()) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        hasPermission = map.values.all { it }
        if (hasPermission) songs = querySongs(context)
    }

    val player = remember { MediaPlayer() }
    val visualizer = remember { arrayOf<Visualizer?>(null) }

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
            // rate 单位 mHz：max/2 = 10Hz 回调
            setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(viz: Visualizer, waveform: ByteArray, samplingRate: Int) {
                        val level = WaveformLevel.level(waveform) // 8 位无符号归一（F04）
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
            try { startReactive(player.audioSessionId) } catch (_: Exception) {}
        } catch (_: Exception) {
            playing = false
        }
    }

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(current?.title ?: "未播放", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        current?.artist ?: if (hasPermission) "从下方曲库选择" else "需要音频读取权限",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1
                    )
                }
                IconButton(onClick = {
                    if (playing) {
                        player.pause(); playing = false; visualizer[0]?.enabled = false
                    } else {
                        current?.let { s ->
                            player.start(); startReactive(player.audioSessionId); playing = true
                        }
                    }
                }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "播放/暂停")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("律动同步", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = reactive,
                    onCheckedChange = {
                        reactive = it
                        if (it && playing) startReactive(player.audioSessionId)
                        else visualizer[0]?.enabled = it && playing
                    }
                )
            }

            if (!hasPermission) {
                Text("需要音频读取权限浏览本地曲目", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Button(onClick = {
                    permLauncher.launch(arrayOf(audioPermissionName(), Manifest.permission.RECORD_AUDIO))
                }) { Text("授予权限") }
            } else if (songs.isEmpty()) {
                Text(
                    "设备中没有可播放的音频",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(songs, key = { it.id }) { song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { current = song; play(song) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
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
        }
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

// ━━━ 灯端拾音设置（类型=帧#9 / 灵敏度=帧#10；对灯端生效，与声源无关）━━━

private val micTypeNames = listOf("经典", "迪斯科", "动感", "柔和")
private val micTypeValues = listOf(1, 4, 3, 2) // 经典=1 迪斯科=4 动感=3 柔和=2（mic$2 开关表实证）

@Composable
private fun LampTuningCard(vm: AppViewModel) {
    val mic by vm.mic.collectAsState()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("灯端拾音设置", style = MaterialTheme.typography.titleMedium)
            Text(
                "拾音类型与灵敏度对灯端麦克风生效",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                micTypeNames.forEachIndexed { i, name ->
                    val value = micTypeValues[i]
                    Surface(
                        shape = CircleShape,
                        color = if (mic.type == value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (mic.type == value) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { vm.setMicType(value) }
                    ) {
                        Text(
                            name, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${mic.sensitivity}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text("灵敏度", style = MaterialTheme.typography.titleSmall)
            }
            Slider(
                value = mic.sensitivity.toFloat(),
                onValueChange = { vm.updateMicSensitivity(it.toInt()) },
                onValueChangeFinished = { vm.sendMicSensitivity() },
                valueRange = 0f..100f
            )
        }
    }
}
