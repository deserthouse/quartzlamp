package io.github.deserthouse.quartzlamp.ui.screens

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.ScannedLamp
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
    val scanning by vm.scanning.collectAsState()
    val known by vm.knownLamps.collectAsState()

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
    // AV04-07：永久拒绝态（系统框不再弹出）需独立入口——"授权蓝牙"按钮此时已无效
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        granted = map.values.all { it }
        permanentlyDenied = !granted && needed.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(context as ComponentActivity, it)
        }
    }

    // 回前台重检权限态（从系统设置授权返回时自动恢复）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = needed.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (granted) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val openAppSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

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
            scanning = scanning,
            granted = granted,
            permanentlyDenied = permanentlyDenied,
            btOn = btOn,
            sessions = sessions,
            scanned = scanned,
            known = known,
            onPermRequest = { permLauncher.launch(needed.toTypedArray()) },
            onEnableBt = { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            onOpenAppSettings = openAppSettings
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
                        .semantics {
                            contentDescription = if (powerOn) "关闭电源（当前已开）" else "打开电源（当前已关）"
                        }
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
    scanning: Boolean,
    granted: Boolean,
    permanentlyDenied: Boolean,
    btOn: Boolean,
    sessions: Map<String, SessionState>,
    scanned: List<io.github.deserthouse.quartzlamp.ble.ScannedLamp>,
    known: Map<String, ScannedLamp>,
    onPermRequest: () -> Unit,
    onEnableBt: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连接管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (scanning) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp))
                }
                if (!granted) {
                    if (permanentlyDenied) {
                        // AV04-07：永久拒绝下系统框不再出现，改走应用设置页
                        Button(onClick = onOpenAppSettings) { Text("前往设置开启") }
                    } else {
                        Button(onClick = onPermRequest) { Text("授权蓝牙") }
                    }
                } else if (scanning) {
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

            if (!granted && permanentlyDenied) {
                Text(
                    "蓝牙权限已被拒绝且系统不再弹窗，请前往系统设置 → 应用权限手动开启",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
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

            // R20-07：discovered ∪ sessions 合并——已连接/连接中设备不因重新扫描消失；
            // AV04-05：身份优先取 knownLamps 登记（重扫清空 discovered 的瞬间不再显示占位名/伪 RSSI）
            val mergedDevices = (scanned.map { it.mac }.toSet() + sessions.keys)
                .map { mac ->
                    val lamp = known[mac]
                        ?: scanned.firstOrNull { it.mac == mac }
                        ?: ScannedLamp(mac.take(5) + "…", mac, Int.MIN_VALUE)
                    lamp to (sessions[mac] ?: SessionState.DISCONNECTED)
                }
                .sortedWith(compareBy({ it.second != SessionState.CONNECTED }, { -it.first.rssi }))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(mergedDevices, key = { it.first.mac }) { (lamp, session) ->
                    // M3E 行容器：圆角 Surface + 连接态着色；自绘行（ListItem 自绘底色会覆盖着色）
                    val connected = session == SessionState.CONNECTED
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (connected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    lamp.name,
                                    color = if (connected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    // AV04-05：无测量值（占位 fallback）不得伪装成 -2147483648 dBm
                                    if (lamp.rssi != Int.MIN_VALUE) "${lamp.mac} · ${lamp.rssi} dBm"
                                    else "${lamp.mac} · 信号待更新",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (connected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            when (session) {
                                SessionState.CONNECTED ->
                                    TextButton(onClick = { vm.disconnect(lamp.mac) }) { Text("断开") }
                                SessionState.CONNECTING ->
                                    // AV04-05：连接中/重连中提供按 MAC 的取消动作（此前无入口）
                                    TextButton(onClick = { vm.disconnect(lamp.mac) }) { Text("取消") }
                                SessionState.RECONNECTING ->
                                    TextButton(onClick = { vm.disconnect(lamp.mac) }) { Text("取消") }
                                SessionState.DISCONNECTED ->
                                    TextButton(onClick = { vm.connect(lamp) }) { Text("连接") }
                            }
                        }
                    }
                }
            }

            if (granted && scanned.isEmpty() && !scanning) {
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

@OptIn(ExperimentalLayoutApi::class)
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
            // AV04-06：FlowRow+胶囊形——大字下"迪斯科"完整显示，不再被 CircleShape 裁成异形椭圆
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                micTypeNames.forEachIndexed { i, name ->
                    val value = micTypeValues[i]
                    val selected = mic.type == value
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .semantics {
                                contentDescription = "拾音类型 $name${if (selected) "（已选中）" else ""}"
                            }
                            .clickable { vm.setMicType(value) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        ) {
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
