package io.github.deserthouse.quartzlamp.ui.screens

import android.Manifest
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

/** 扫描连接页：BLE 权限门 → 扫描（自动过滤拾音灯广播 22 20）→ 按行连接/断开（F07 行级会话） */
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
                    Button(onClick = vm::startScan) { Text("扫描") }
                }
            } else {
                Button(onClick = { permLauncher.launch(needed.toTypedArray()) }) { Text("授权蓝牙") }
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
