package io.github.deserthouse.quartzlamp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import io.github.deserthouse.quartzlamp.ui.AppViewModel
import io.github.deserthouse.quartzlamp.ui.screens.ColorScreen
import io.github.deserthouse.quartzlamp.ui.screens.MicScreen
import io.github.deserthouse.quartzlamp.ui.screens.ModeScreen
import io.github.deserthouse.quartzlamp.ui.screens.MusicScreen
import io.github.deserthouse.quartzlamp.ui.screens.ScanScreen
import io.github.deserthouse.quartzlamp.ui.screens.TimerScreen
import io.github.deserthouse.quartzlamp.ui.theme.QuartzLampTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels { AppViewModel.factory(application as QuartzLampApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuartzLampTheme {
                MainScaffold(vm)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("color", "调色", Icons.Filled.Palette),
    Tab("mode", "模式", Icons.Filled.Speed),
    Tab("mic", "麦克风", Icons.Filled.GraphicEq),
    Tab("music", "音乐", Icons.Filled.MusicNote),
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
                    Text(
                        "设备",
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clickable { nav.navigate("scan") },
                        style = MaterialTheme.typography.labelLarge
                    )
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
            composable("mic") { MicScreen(vm) }
            composable("music") { MusicScreen(vm) }
            composable("timer") { TimerScreen(vm) }
            composable("scan") { ScanScreen(vm) }
        }
    }
}
