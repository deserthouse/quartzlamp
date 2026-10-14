package io.github.deserthouse.quartzlamp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.deserthouse.quartzlamp.audio.MicEngine
import io.github.deserthouse.quartzlamp.ui.AppViewModel

private val micTypes = listOf("经典", "迪斯科", "动感", "柔和")

/** 麦克风页：声源（手机麦/外麦）+ 拾音类型 + 灵敏度；手机麦开启即本地采音驱动律动帧 */
@Composable
fun MicScreen(vm: AppViewModel) {
    val mic by vm.mic.collectAsState()
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    val engine = remember { MicEngine() }

    DisposableEffect(Unit) {
        onDispose { engine.stop() }
    }

    // 手机麦模式 + 权限在手 → 启动采音，响度驱动律动帧（色相 265°→0° 环扫）
    DisposableEffect(mic.phoneMic, hasPermission) {
        if (mic.phoneMic && hasPermission) {
            engine.start(kotlinx.coroutines.MainScope()) { level ->
                val hue = ((1f - level) * 265f).toInt()
                vm.sendReactive(hue, 1f)
            }
        } else {
            engine.stop()
        }
        onDispose { engine.stop() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("声源", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mic.phoneMic,
                onClick = { vm.setMicSource(true) },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) { Text("手机麦") }
            SegmentedButton(
                selected = !mic.phoneMic,
                onClick = { vm.setMicSource(false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) { Text("外麦") }
        }

        Text("拾音类型", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            micTypes.forEachIndexed { i, name ->
                FilterChip(
                    selected = mic.type == i + 1,
                    onClick = { vm.setMicType(i + 1) },
                    label = { Text(name) }
                )
            }
        }

        Text("灵敏度 ${mic.sensitivity}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = mic.sensitivity.toFloat(),
            onValueChange = { vm.setMicSensitivity(it.toInt()) },
            valueRange = 0f..100f
        )

        if (mic.phoneMic && !hasPermission) {
            Text(
                "需要麦克风权限驱动律动",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.material3.Button(onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("授予麦克风权限")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
