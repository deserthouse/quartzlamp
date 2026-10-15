package io.github.deserthouse.quartzlamp.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.SessionState
import io.github.deserthouse.quartzlamp.ui.AppViewModel

private fun bluetoothEnabled(context: Context): Boolean =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter?.isEnabled == true

/** 扫描连接页：BLE 权限门 → 蓝牙开关检测 → 扫描（自动过滤拾音灯广播 22 20）→ 按行连接/断开 */
@Composable
fun ScanScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val scanned by vm.scanned.collectAsState()
    val sessions by vm.sessions.collectAsState()

    val needed = remember {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
    var granted by remember {
        mutableStateOf(needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map -> granted = map.values.all { it } }

    // 蓝牙开关态（系统开关变化实时反映，审查 §6.3 设备页状态）
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

    DisposableEffect(Unit) {
        onDispose { vm.stopScan() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state) {
                    ConnState.DISCONNECTED -> "未连接"
                    ConnState.SCANNING -> "扫描中…"
                    ConnState.CONNECTING -> "连接中…"
                    ConnState.CONNECTED -> "已连接 ×${sessions.count { it.value == SessionState.CONNECTED }}"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (vm.isDemo) {
                Text(
                    "演示模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            if (state == ConnState.SCANNING) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
            }
            if (granted) {
                if (state == ConnState.SCANNING) {
                    TextButton(onClick = vm::stopScan) { Text("停止") }
                } else {
                    Button(
                        onClick = vm::startScan,
                        enabled = btOn  // 蓝牙关闭时扫描不可点（避免必失败请求）
                    ) { Text("扫描") }
                }
            } else {
                Button(onClick = { permLauncher.launch(needed.toTypedArray()) }) { Text("授权蓝牙") }
            }
        }

        // 蓝牙关闭态（权限与开关是两个独立前提）
        if (granted && !btOn) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "蓝牙未开启，无法扫描与连接",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        modifier = Modifier.padding(start = 12.dp)
                    ) { Text("去开启") }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(scanned, key = { it.mac }) { lamp ->
                val session = sessions[lamp.mac] ?: SessionState.DISCONNECTED
                ListItem(
                    headlineContent = { Text(lamp.name) },
                    supportingContent = { Text("${lamp.mac} · ${lamp.rssi} dBm") },
                    trailingContent = {
                        // 行级会话状态：只反映本设备，不因其他设备连接而变化（F07）
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
