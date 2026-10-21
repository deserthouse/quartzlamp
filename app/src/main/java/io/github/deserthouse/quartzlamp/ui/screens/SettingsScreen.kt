package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.BuildConfig
import io.github.deserthouse.quartzlamp.R
import io.github.deserthouse.quartzlamp.QuartzLampApp
import io.github.deserthouse.quartzlamp.ui.theme.IrisDark
import io.github.deserthouse.quartzlamp.ui.theme.IrisLight

private enum class SettingsDetail { NAME, LICENSE }

/** 外观优先；应用身份、项目入口与补充说明各自成组，避免长文与设置争夺注意力。 */
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as QuartzLampApp
    val ctx = LocalContext.current
    val dynamic by app.dynamicTheme.collectAsState()
    val dark = isSystemInDarkTheme()
    val quartzColors = if (dark) IrisDark else IrisLight
    val systemColors = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    var detail by remember { mutableStateOf<SettingsDetail?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("外观")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice(
                    title = "石英色板", description = "雾紫与暖砂，固定配色",
                    colors = quartzColors, icon = Icons.Outlined.Palette,
                    selected = !dynamic, onSelect = { app.setDynamicTheme(false) }
                )
                ThemeChoice(
                    title = "系统动态色", description = "从你的壁纸中取色",
                    colors = systemColors, icon = Icons.Outlined.AutoAwesome,
                    selected = dynamic, onSelect = { app.setDynamicTheme(true) }
                )
            }
            Text(
                "深浅模式跟随系统，两种配色均可使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("关于拾音灯")
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.large)) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.app_name_en),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsRow("项目主页", "源码、更新与使用说明", Icons.Outlined.Code, first = true) {
                    openUrl(ctx, "https://github.com/deserthouse/quartzlamp")
                }
                SettingsRow("澪狼 · Ling the Wolp", "@deserthouse", avatar = true) {
                    openUrl(ctx, "https://github.com/deserthouse")
                }
                SettingsRow("名字的由来", "拾音与石英的小小谐音", Icons.Outlined.Info, last = true, external = false) {
                    detail = SettingsDetail.NAME
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("开源与说明")
            SettingsRow("开源许可", "Apache 2.0 · FastBle", Icons.Outlined.Description,
                first = true, last = true, external = false) { detail = SettingsDetail.LICENSE }
            Text(
                "独立开发的蓝牙拾音灯控制器，与 Lenze Tech 无关联。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Spacer(Modifier.size(8.dp))
    }

    detail?.let { selected ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(if (selected == SettingsDetail.NAME) "名字的由来" else "开源许可") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selected == SettingsDetail.NAME) {
                        Text("拾音（shíyīn）与石英（shíyīng）听起来很像。QuartzLamp 的名字借用了这点巧合：一盏会听声音的灯。")
                    } else {
                        Text("本应用以 Apache License 2.0 开源。蓝牙通信使用 Jasonchenlijian 的 FastBle 2.3.4，同样采用 Apache License 2.0。")
                        Text("软件按现状提供，不作任何担保。完整许可与第三方声明可在项目主页查看。")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("知道了") } },
            dismissButton = {
                if (selected == SettingsDetail.LICENSE) {
                    TextButton(onClick = { openUrl(ctx, "https://github.com/deserthouse/quartzlamp/blob/main/LICENSE") }) {
                        Text("完整许可")
                    }
                }
            }
        )
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
}

@Composable
private fun ThemeChoice(
    title: String, description: String, colors: ColorScheme, icon: ImageVector,
    selected: Boolean, onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.width(40.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(colors.primary, colors.secondary, colors.tertiary).forEach { color ->
                        Box(Modifier.size(12.dp).background(color, CircleShape))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        // DEF-1：失败给可见反馈，不再静默
        android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 组外圆角、组内小圆角；整行可点且高度随内容增长，大字不固定行高。 */
@Composable
private fun SettingsRow(
    label: String, summary: String, icon: ImageVector? = null, avatar: Boolean = false,
    first: Boolean = false, last: Boolean = false, external: Boolean = true, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = if (first) 24.dp else 4.dp, topEnd = if (first) 24.dp else 4.dp,
        bottomStart = if (last) 24.dp else 4.dp, bottomEnd = if (last) 24.dp else 4.dp
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (avatar) {
            Image(painterResource(R.drawable.avatar_deserthouse), null, Modifier.size(32.dp).clip(CircleShape))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(32.dp).padding(4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (external) Icon(Icons.AutoMirrored.Outlined.Launch, contentDescription = "在浏览器打开", modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
