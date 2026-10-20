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
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.BuildConfig
import io.github.deserthouse.quartzlamp.R
import io.github.deserthouse.quartzlamp.QuartzLampApp

/** 设置页（V 系列 P2，由 About 迁入）：主题双轨开关 + 关于 + 声明。入口=顶栏右上 ⚙️。 */
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as QuartzLampApp
    val ctx = LocalContext.current
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
                // 三行头（Prunoid 关于卡样板）：名 / 版本 / 描述
                Text(
                    "拾音灯 / ${stringResource(R.string.app_name_en)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "v${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "拾音（shíyīn）≈ 石英（shíyīng）——原厂包名 quartzlamp 的谐音玩笑，" +
                        "中英文名互为对方的谐音：一盏名字听着像另一种灯的、会听声音的灯。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AboutLinkRow(
                    ctx, "GitHub 项目主页", "github.com/deserthouse/quartzlamp",
                    "https://github.com/deserthouse/quartzlamp"
                )
                // 作者行（点击进作者主页）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { openUrl(ctx, "https://github.com/deserthouse") }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painterResource(R.drawable.avatar_deserthouse),
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("澪狼", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "@deserthouse",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "Ling the Wolp",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "图标基于 Twemoji（Twitter, Inc. 及贡献者，CC-BY 4.0）",
                    style = MaterialTheme.typography.labelMedium,
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

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** 项目/作者链接行（Prunoid 关于卡样式）：label + primary 值 + Launch 图标，整行 48dp 触区 */
@Composable
private fun AboutLinkRow(ctx: Context, label: String, value: String, url: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { openUrl(ctx, url) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.Launch,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
