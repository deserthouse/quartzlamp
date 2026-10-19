package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.data.Effects
import io.github.deserthouse.quartzlamp.ui.AppViewModel

// ━━━ 灯效名 → 示意色（客观编码名称中的颜色词；预览明确标注"示意"，不冒充固件动画） ━━━
private val singleColorMap: Map<Char, Color> = mapOf(
    '蓝' to Color(0xFF2E7DF6),
    '绿' to Color(0xFF35C759),
    '红' to Color(0xFFFF3B30),
    '黄' to Color(0xFFFFCC00),
    '紫' to Color(0xFF9B5DE5),
    '白' to Color(0xFFF2F2F7),
    '青' to Color(0xFF32ADE6)
)

private val rainbow = listOf(
    Color(0xFF2E7DF6), Color(0xFF32ADE6), Color(0xFF35C759),
    Color(0xFFFFCC00), Color(0xFFFF9500), Color(0xFFFF3B30), Color(0xFF9B5DE5)
)

/** 提取效果名里的颜色（按出现序取 ≤2 个不同色；七彩=彩虹；"蓝白"→蓝+白） */
internal fun hintColors(name: String): List<Color> {
    if (name.contains("七彩")) return rainbow
    val found = mutableListOf<Color>()
    for (ch in name) {
        val c = singleColorMap[ch]
        if (c != null && found.none { it == c }) {
            found += c
            if (found.size == 2) break
        }
    }
    return found.ifEmpty { listOf(Color(0xFF8E8E93)) }
}

/** 示意条：名称颜色渐变 + （有点类）白点标记 */
@Composable
private fun HintBar(name: String, modifier: Modifier) {
    val colors = hintColors(name)
    val hasDot = name.contains("点、")
    Canvas(modifier.height(10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(
            brush = when (colors.size) {
                1 -> Brush.horizontalGradient(listOf(colors[0], colors[0]))
                else -> Brush.horizontalGradient(colors)
            },
            size = size
        )
        if (hasDot) {
            drawCircle(Color.White, radius = size.height * 0.55f, center = Offset(size.width * 0.25f, size.height / 2))
        }
    }
}

/**
 * 灯效屏（光谱控制台样张，审查 §6.3）：
 * 顶部"当前灯效"预览（完整原名+示意）；分类 pill 可发现；双列效果卡（完整原名）；
 * 速度/灵敏度整合为底部控制组。限幅按当前浏览分类=厂商语义（mode$4 汇合点实证）。
 */
@Composable
fun ModeScreen(vm: AppViewModel) {
    val mode by vm.mode.collectAsState()
    val conn by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(mode.tab) }
    // 厂商同语义：isInQuandengMode 由 mode$4.onTabSelected 汇合点(01d9)在每次切 tab 时按"当前tab==全灯tab"
    // 重估（全 dex 仅此一处写点），故按浏览 tab 判定即厂商行为；速度限幅=上限70
    val quandeng = tab == 8

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("灯效", style = MaterialTheme.typography.headlineLarge)

        // ━━━ 当前灯效预览卡 ━━━
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "当前灯效",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // 有依据的状态（R19-08）：未连接不得伪装"运行中"
                    if (conn == io.github.deserthouse.quartzlamp.ble.ConnState.CONNECTED) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                "运行中",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                "未连接",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Text(
                    Effects.nameOf(mode.selected),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintBar(Effects.nameOf(mode.selected), Modifier.weight(1f))
                    Text("示意", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // ━━━ 分类 pills（可发现）━━━
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Effects.categories.forEachIndexed { i, name ->
                val selected = tab == i
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { tab = i }
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }

        // ━━━ 双列效果卡（完整原名 + 示意条）━━━
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(Effects.effects[tab]) { pos, name ->
                val deviceIndex = Effects.deviceIndex(tab, pos)
                val selected = mode.selected == deviceIndex
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = MaterialTheme.shapes.medium
                        )
                        .clickable { vm.selectEffect(tab, pos, deviceIndex) }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check, contentDescription = "已选中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp).padding(start = 4.dp)
                                )
                            }
                        }
                        HintBar(name, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // ━━━ 底部控制组：速度 + 灵敏度 ━━━
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlSlider(
                    label = "速度", value = mode.speed, range = 0f..100f,
                    quandengNote = if (quandeng) "（全灯类上限 70）" else null,
                    onDrag = { vm.updateSpeed(it) },
                    onRelease = { vm.sendSpeed(quandeng) }
                )
                Spacer(Modifier.height(4.dp))
                ControlSlider(
                    label = "灵敏度", value = mode.sensitivity, range = 0f..100f,
                    quandengNote = null,
                    onDrag = { vm.updateSensitivity(it) },
                    onRelease = { vm.sendSensitivity() }
                )
            }
        }
    }
}

@Composable
private fun ControlSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    quandengNote: String?,
    onDrag: (Int) -> Unit,
    onRelease: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$value",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                label + (quandengNote ?: ""),
                style = MaterialTheme.typography.titleSmall
            )
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onDrag(it.toInt()) },
            onValueChangeFinished = onRelease,
            valueRange = range
        )
    }
}
