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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.IconButton
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.ui.AppViewModel
import io.github.deserthouse.quartzlamp.ui.screens.DeviceScreen
import io.github.deserthouse.quartzlamp.ui.screens.LightsScreen
import io.github.deserthouse.quartzlamp.ui.screens.SettingsScreen
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

/** 三 tab（V 系列定案）：设备（起始，D4——每次启动先连接）/ 灯光（单色|灯效）/ 定时。 */
private val tabs = listOf(
    Tab("device", "设备", Icons.Outlined.Bluetooth),
    Tab("lights", "灯光", Icons.Filled.Lightbulb),
    Tab("timer", "定时", Icons.Filled.Schedule),
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val connLabel = when (state) {
        ConnState.CONNECTED -> "已连接"
        ConnState.CONNECTING -> "连接中"
        ConnState.SCANNING -> "扫描中"
        ConnState.DISCONNECTED -> "未连接"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拾音灯 · $connLabel") },
                actions = {
                    IconButton(
                        onClick = { nav.navigate("settings") { launchSingleTop = true } },
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            if (currentRoute != "settings") {
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
            startDestination = "device", // D4：冷启固定落设备 tab
            modifier = Modifier.padding(padding),
            // U6 预测式返回配套：水平滑动转场（进入右滑入，返回镜像）
            enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { slideOutHorizontally { -it / 4 } + fadeOut() },
            popEnterTransition = { slideInHorizontally { -it / 4 } + fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() }
        ) {
            composable("device") { DeviceScreen(vm) }
            composable("lights") { LightsScreen(vm) }
            composable("timer") { TimerScreen(vm) }
            composable("settings") { SettingsScreen() }
        }
    }
}
