package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.BuildConfig
import io.github.deserthouse.quartzlamp.QuartzLampApp

/** 设置页（V 系列 P2，由 About 迁入）：主题双轨开关 + 关于 + 声明。入口=顶栏右上 ⚙️。 */
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as QuartzLampApp
    val dynamic by app.dynamicTheme.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineLarge)

        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("主题", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !dynamic,
                        onClick = { app.setDynamicTheme(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("石英色板") }
                    SegmentedButton(
                        selected = dynamic,
                        onClick = { app.setDynamicTheme(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("系统动态色") }
                }
                Text(
                    "石英色板为品牌默认；动态色跟随系统壁纸取色",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text(
                    "QuartzLamp · v${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "拾音（shíyīn）≈ 石英（shíyīng）——原厂包名 quartzlamp 的谐音玩笑，" +
                        "中英文名互为对方的谐音：一盏名字听着像另一种灯的、会听声音的灯。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("开源与声明", style = MaterialTheme.typography.titleMedium)
                Text(
                    "BLE 传输层基于 FastBle 2.3.4（Apache-2.0，源码收编于 app/libs-src/）。\n" +
                        "本项目为个人学习/自用目的对自有硬件的控制器重实现，与 Lenze Tech 无关联；" +
                        "软件按现状提供，使用后果自负。完整声明见 README。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
