package io.github.deserthouse.quartzlamp.ui.screens

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.SessionState
import io.github.deserthouse.quartzlamp.ui.AppViewModel

private fun bluetoothEnabled(context: Context): Boolean =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter?.isEnabled == true

/**
 * 设备 tab（V 系列 P3）：电源主操作 + 亮度（全局，D3——唯一亮度入口）+
 * 连接管理（扫描/行级会话/蓝牙开关态）+ 灯端拾音高级卡（类型/灵敏度，D1）。
 */
@Composable
fun DeviceScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val scanned by vm.scanned.collectAsState()
    val sessions by vm.sessions.collectAsState()

    val needed = remember {
        listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
    }
    var granted by remember {
        mutableStateOf(needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map -> granted = map.values.all { it } }

    // 蓝牙开关态（系统开关变化实时反映）
    var btOn by remember { mutableStateOf(bluetoothEnabled(context)) }
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { btOn = bluetoothEnabled(context) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { btOn = bluetoothEnabled(c) }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设备", style = MaterialTheme.typography.headlineLarge)
        PowerBrightnessCard(vm)
        ConnectionCard(
            vm = vm,
            state = state,
            granted = granted,
            btOn = btOn,
            sessions = sessions,
            scanned = scanned,
            onPermRequest = { permLauncher.launch(needed.toTypedArray()) },
            onEnableBt = { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        )
        LampTuningCard(vm)
        Spacer(Modifier.height(8.dp))
    }
}

// ━━━ 电源 + 亮度（BC05 全局亮度唯一入口，D3）━━━

@Composable
private fun PowerBrightnessCard(vm: AppViewModel) {
    val color by vm.color.collectAsState()
    val powerOn by vm.powerOn.collectAsState()

    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("电源与亮度", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = if (powerOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    contentColor = if (powerOn) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable { vm.setPower(!powerOn) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PowerSettingsNew, contentDescription = "电源",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${color.bright}",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text("亮度", style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = color.bright.toFloat(),
                    onValueChange = { vm.updateBrightness(it.toInt()) },
                    onValueChangeFinished = { vm.sendBrightness() },
                    valueRange = 0f..1000f,
                    modifier = Modifier.semantics {
                        contentDescription = "亮度：当前 ${color.bright}"
                    }
                )
            }
            Text(
                "亮度为全局参数：任何模式（单色/灯效）下调整均生效",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ━━━ 连接管理（原扫描页并入）━━━

@Composable
private fun ConnectionCard(
    vm: AppViewModel,
    state: ConnState,
    granted: Boolean,
    btOn: Boolean,
    sessions: Map<String, SessionState>,
    scanned: List<io.github.deserthouse.quartzlamp.ble.ScannedLamp>,
    onPermRequest: () -> Unit,
    onEnableBt: () -> Unit
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连接管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state == ConnState.SCANNING) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).height(20.dp))
                }
                if (!granted) {
                    Button(onClick = onPermRequest) { Text("授权蓝牙") }
                } else if (state == ConnState.SCANNING) {
                    TextButton(onClick = vm::stopScan) { Text("停止") }
                } else {
                    Button(onClick = vm::startScan, enabled = btOn) { Text("扫描") }
                }
            }

            if (vm.isDemo) {
                Text(
                    "演示模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (granted && !btOn) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "蓝牙未开启，无法扫描与连接",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = onEnableBt, modifier = Modifier.padding(start = 12.dp)) {
                            Text("去开启")
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(scanned, key = { it.mac }) { lamp ->
                    val session = sessions[lamp.mac] ?: SessionState.DISCONNECTED
                    ListItem(
                        headlineContent = { Text(lamp.name) },
                        supportingContent = { Text("${lamp.mac} · ${lamp.rssi} dBm") },
                        trailingContent = {
                            when (session) {
                                SessionState.CONNECTED ->
                                    TextButton(onClick = { vm.disconnect(lamp.mac) }) { Text("断开") }
                                SessionState.CONNECTING ->
                                    Text("连接中…", style = MaterialTheme.typography.labelMedium)
                                SessionState.RECONNECTING ->
                                    Text("重连中…", style = MaterialTheme.typography.labelMedium)
                                SessionState.DISCONNECTED ->
                                    TextButton(onClick = { vm.connect(lamp) }) { Text("连接") }
                            }
                        }
                    )
                }
            }

            if (granted && scanned.isEmpty() && state == ConnState.DISCONNECTED) {
                Text(
                    "扫描会自动过滤拾音灯设备；未出现请确认灯已通电",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ━━━ 灯端拾音高级卡（D1：类型 BC11 + 灵敏度 BC12；声源不暴露）━━━

private val micTypeNames = listOf("经典", "迪斯科", "动感", "柔和")
private val micTypeValues = listOf(1, 4, 3, 2) // 经典=1 迪斯科=4 动感=3 柔和=2（mic$2 开关表实证）

@Composable
private fun LampTuningCard(vm: AppViewModel) {
    val mic by vm.mic.collectAsState()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("灯端拾音", style = MaterialTheme.typography.titleMedium)
            Text(
                "灯自带麦克风的行为配置：对灯说话或放音乐即触发灯效律动",
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
                            .heightIn(min = 48.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = "拾音类型 $name" }
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
