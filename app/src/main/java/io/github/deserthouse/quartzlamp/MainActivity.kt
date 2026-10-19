package io.github.deserthouse.quartzlamp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ble.SessionState
import io.github.deserthouse.quartzlamp.ui.AppViewModel
import io.github.deserthouse.quartzlamp.ui.screens.AboutScreen
import io.github.deserthouse.quartzlamp.ui.screens.ColorScreen
import io.github.deserthouse.quartzlamp.ui.screens.ModeScreen
import io.github.deserthouse.quartzlamp.ui.screens.ScanScreen
import io.github.deserthouse.quartzlamp.ui.screens.TimerScreen
import io.github.deserthouse.quartzlamp.ui.theme.QuartzLampTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels { AppViewModel.factory(application as QuartzLampApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as QuartzLampApp
        setContent {
            val dynamic by app.dynamicTheme.collectAsState()
            QuartzLampTheme(dynamicColor = dynamic) {
                MainScaffold(vm)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** 四入口（2026-10-15 拍板）：调色/灯效/律动/定时；扫描收进顶栏设备胶囊 */
private val tabs = listOf(
    Tab("color", "调色", Icons.Filled.Palette),
    Tab("mode", "灯效", Icons.Filled.AutoAwesome),
    Tab("timer", "定时", Icons.Filled.Schedule),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val connectedCount = sessions.count { it.value == SessionState.CONNECTED }
    val connLabel = when {
        state == ConnState.SCANNING -> "扫描中"
        connectedCount > 0 -> "已连接 ×$connectedCount"
        state == ConnState.CONNECTING -> "连接中"
        else -> "未连接"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拾音灯 · $connLabel") },
                actions = {
                    // 全局设备胶囊（连接管理入口）
                    Surface(
                        shape = CircleShape,
                        color = if (connectedCount > 0) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (connectedCount > 0) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clickable { nav.navigate("scan") }
                    ) {
                        Text(
                            "设备",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            if (currentRoute != "scan") {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "color",
            modifier = Modifier.padding(padding)
        ) {
            composable("color") { ColorScreen(vm) }
            composable("mode") { ModeScreen(vm) }
            composable("timer") { TimerScreen(vm) }
            composable("scan") { ScanScreen(vm, onAbout = { nav.navigate("about") }) }
            composable("about") { AboutScreen() }
        }
    }
}
