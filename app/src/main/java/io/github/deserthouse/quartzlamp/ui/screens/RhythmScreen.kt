package io.github.deserthouse.quartzlamp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.deserthouse.quartzlamp.audio.MicEngine
import io.github.deserthouse.quartzlamp.audio.WaveformLevel
import io.github.deserthouse.quartzlamp.ui.AppViewModel
import io.github.deserthouse.quartzlamp.ui.RhythmSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Song(val title: String, val artist: String, val uri: Uri, val id: Long)

/** 音乐源状态（屏幕级持有——曲库条目展开进主滚动流后，面板组合不再整体存在，R19-08） */
private class MusicState {
    val player = MediaPlayer()
    val visualizer = arrayOf<Visualizer?>(null)
    var songs by mutableStateOf(listOf<Song>())
    var current by mutableStateOf<Song?>(null)
    var playing by mutableStateOf(false)
    var preparing by mutableStateOf(false)
    var reactive by mutableStateOf(true)
    var reactiveUnavailable by mutableStateOf(false)
    var musicError by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadError by mutableStateOf(false)
    var hasPermission by mutableStateOf(false)
    var positionMs by mutableStateOf(0)
    var durationMs by mutableStateOf(0)
}

/** ON_STOP 即执行（R19-05：DisposableEffect 只覆盖组合离开，不覆盖 Activity 后台） */
@Composable
private fun OnLifecycleStop(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) action() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** 音乐读取权限按 API 分支：READ_MEDIA_AUDIO（33+）/ READ_EXTERNAL_STORAGE（31/32） */
private fun audioPermissionName(): String =
    if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * 律动屏（光谱控制台；R19-08：整页 LazyColumn——源面板与曲库条目都在主滚动流内，
 * 360dp×2.0 大字体下无不可达控件；无固定高度列表）。
 */
@Composable
fun RhythmScreen(vm: AppViewModel) {
    val source by vm.rhythmSource.collectAsState()
    val context = LocalContext.current
    val music = remember { MusicState() }

    // ━━━ 音乐：屏幕级状态与副作用 ━━━
    fun startReactive(sessionId: Int) {
        music.visualizer[0]?.release()
        music.visualizer[0] = null
        if (!music.reactive) return
        val v = Visualizer(sessionId).apply {
            captureSize = Visualizer.getCaptureSizeRange()[0]
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
        music.visualizer[0] = v
    }

    fun play(song: Song) {
        music.musicError = false
        music.reactiveUnavailable = false
        try {
            music.player.reset()
            music.player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            music.player.setDataSource(context, song.uri)
            music.preparing = true
            music.player.prepareAsync() // 异步（R19-05）：完成回调在 DisposableEffect 注册
        } catch (_: Exception) {
            music.preparing = false
            music.musicError = true
        }
    }

    DisposableEffect(Unit) {
        music.player.setOnCompletionListener {
            music.playing = false
            music.preparing = false
            music.positionMs = music.durationMs
            music.visualizer[0]?.enabled = false
        }
        music.player.setOnPreparedListener { p ->
            music.preparing = false
            p.start()
            music.playing = true
            music.durationMs = try { p.duration } catch (_: Exception) { 0 }
            try {
                startReactive(p.audioSessionId)
            } catch (_: Exception) {
                music.reactiveUnavailable = true // 部分 HAL 不支持效果捕获：播放不受影响
            }
        }
        music.player.setOnErrorListener { _, _, _ ->
            music.preparing = false; music.playing = false; music.musicError = true; true
        }
        onDispose {
            music.visualizer[0]?.release()
            music.visualizer[0] = null
            music.player.release()
        }
    }

    // Home/锁屏即暂停（R19-05：无后台服务，后台续播不在范围）
    OnLifecycleStop {
        if (music.playing || music.preparing) {
            try {
                music.player.pause()
            } catch (_: IllegalStateException) {
            }
            music.playing = false
            music.visualizer[0]?.enabled = false
        }
    }

    // 曲库加载（R19-02）：已授权进入/重进/授权回调三路统一（IO 线程，三态）
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        music.hasPermission = map.values.all { it }
    }
    LaunchedEffect(Unit) {
        music.hasPermission = ContextCompat.checkSelfPermission(context, audioPermissionName()) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(music.hasPermission) {
        if (music.hasPermission) {
            music.loading = true; music.loadError = false
            try {
                music.songs = withContext(Dispatchers.IO) { querySongs(context) }
            } catch (_: Exception) {
                music.loadError = true
            }
            music.loading = false
        }
    }
    LaunchedEffect(music.playing, music.current?.id) {
        while (music.playing) {
            music.positionMs = try { music.player.currentPosition } catch (_: IllegalStateException) { 0 }
            delay(500)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("律动", style = MaterialTheme.typography.headlineLarge) }

        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RhythmSource.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = source == s,
                        onClick = { vm.selectRhythmSource(s) },
                        shape = SegmentedButtonDefaults.itemShape(i, RhythmSource.entries.size),
                        icon = {}
                    ) { Text(s.label) }
                }
            }
        }

        when (source) {
            RhythmSource.PHONE_MIC -> item { PhoneMicPanel(vm) }
            RhythmSource.LAMP_MIC -> item { LampMicPanel() }
            RhythmSource.MUSIC -> {
                item { MusicHeaderCard(music, permLauncher, ::play, ::startReactive) }
                when {
                    !music.hasPermission -> item {
                        Text(
                            "授予权限后浏览本地曲目",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    music.loading -> item { CircularProgressIndicator(Modifier.size(28.dp)) }
                    music.loadError -> item {
                        Text("曲目加载失败，重进本页重试", color = MaterialTheme.colorScheme.error)
                    }
                    music.songs.isEmpty() -> item {
                        Text(
                            "设备中没有可播放的音频",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> items(music.songs, key = { it.id }) { song ->
                        SongRow(song, music, ::play)
                    }
                }
            }
        }

        item { LampTuningCard(vm) }
        item { Spacer(Modifier.height(8.dp)) }
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
    val engineScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var micError by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            engine.stop()
            engineScope.cancel()
        }
    }

    // Home/锁屏即停采音（R19-05）
    OnLifecycleStop { if (listening) listening = false }

    DisposableEffect(listening, hasPermission) {
        if (listening && hasPermission) {
            vm.setMicSource(true)
            micError = false
            engine.start(
                engineScope,
                { l ->
                    level = l
                    vm.sendReactive(((1f - l) * 265f).toInt(), 1f)
                },
                { micError = true; listening = false }
            )
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
                        .semantics { contentDescription = if (listening) "停止聆听" else "开始聆听" }
                        .clickable {
                            if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else listening = !listening
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Mic, contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

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
                when {
                    !hasPermission -> "需要麦克风权限驱动律动"
                    micError -> "麦克风启动失败（被占用或权限被撤），请重试"
                    else -> "响度映射色相：安静深蓝 → 高潮红"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (!hasPermission || micError) MaterialTheme.colorScheme.error
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

// ━━━ 音乐：头部卡（播放控制/律动开关/进度/状态）━━━

private fun fmt(ms: Int): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
private fun MusicHeaderCard(
    music: MusicState,
    permLauncher: ActivityResultLauncher<Array<String>>,
    play: (Song) -> Unit,
    startReactive: (Int) -> Unit
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(music.current?.title ?: "未播放", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        when {
                            music.musicError -> "播放失败，请重试"
                            music.preparing -> "准备中…"
                            music.reactiveUnavailable -> "播放正常，但该设备不支持波形捕获（律动不可用）"
                            else -> music.current?.artist ?: "从下方曲库选择"
                        },
                        style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        color = if (music.musicError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (music.preparing) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    IconButton(onClick = {
                        if (music.playing) {
                            music.player.pause(); music.playing = false; music.visualizer[0]?.enabled = false
                        } else {
                            music.current?.let { s ->
                                music.player.start(); startReactive(music.player.audioSessionId); music.playing = true
                            }
                        }
                    }) {
                        Icon(if (music.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "播放/暂停")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("律动同步", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = music.reactive,
                    onCheckedChange = {
                        music.reactive = it
                        if (it && music.playing) startReactive(music.player.audioSessionId)
                        else music.visualizer[0]?.enabled = it && music.playing
                    }
                )
            }

            if (music.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { music.positionMs.toFloat() / music.durationMs },
                    modifier = Modifier.fillMaxWidth()
                )
                Row {
                    Text(fmt(music.positionMs), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(fmt(music.durationMs), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (!music.hasPermission) {
                Button(onClick = {
                    permLauncher.launch(arrayOf(audioPermissionName(), Manifest.permission.RECORD_AUDIO))
                }) { Text("授予权限") }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, music: MusicState, play: (Song) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { music.current = song; play(song) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            song.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            style = MaterialTheme.typography.bodyLarge,
            color = if (music.current?.id == song.id) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        FilledIconButton(onClick = { music.current = song; play(song) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "播放 ${song.title}")
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                micTypeNames.forEachIndexed { i, name ->
                    val value = micTypeValues[i]
                    val selected = mic.type == value
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp) // 触区 ≥48dp（R19-08）
                            .clip(CircleShape)
                            .semantics {
                                role = Role.Button
                                contentDescription = "拾音类型 $name"
                            }
                            .clickable { vm.setMicType(value) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(name, style = MaterialTheme.typography.labelLarge)
                        }
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
