package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.ble.ConnState
import io.github.deserthouse.quartzlamp.data.Effects
import io.github.deserthouse.quartzlamp.ui.AppViewModel
import io.github.deserthouse.quartzlamp.ui.LampMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 灯光子模式（页内分段） */
private enum class LightsPane(val label: String) { STATIC("单色"), EFFECT("灯效") }

// ━━━ 色彩工具（自 ColorScreen 迁入，HueMathTest 同包引用）━━━

internal fun colorOf(h: Int, s: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s.coerceIn(0f, 1f), 1f)))

/**
 * 相对圆心的角度 → 色相（R19-06 修复）：0°=正右（3 点钟）顺时针，
 * 与 Brush.sweepGradient 色序原点一致。
 */
internal fun hueAt(dx: Double, dy: Double): Int {
    val deg = Math.toDegrees(atan2(dy, dx))
    return ((deg % 360.0 + 360.0) % 360.0).toInt()
}

/** 环带命中：仅色环带可拾取（中心色盘与环外无效） */
internal fun inRingBand(dist: Double, ringR: Double, halfStroke: Double, slack: Double = 0.0): Boolean =
    kotlin.math.abs(dist - ringR) <= halfStroke + slack

internal data class Preset(val hue: Int, val sat: Float, val name: String)

private val presets = listOf(
    Preset(0, 1f, "红"),
    Preset(35, 1f, "橙"),
    Preset(60, 1f, "黄"),
    Preset(120, 1f, "绿"),
    Preset(180, 1f, "青"),
    Preset(210, 1f, "蓝"),
    Preset(265, 1f, "紫"),
    Preset(300, 1f, "品红")
)

/**
 * 灯光 tab（V 系列合并页，U3/U4）：`单色|灯效` 分段=灯模式互斥选择。
 * 单色=色环+预设+饱和度（无亮度——D3，亮度在设备页）；
 * 灯效=当前效果条+分类+双列网格+速度/模式灵敏度（限幅跟已选效果）。
 */
@Composable
fun LightsScreen(vm: AppViewModel) {
    val lampMode by vm.lampMode.collectAsState()
    var pane by remember { mutableIntStateOf(if (lampMode is LampMode.Static) 0 else 1) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("灯光", style = MaterialTheme.typography.headlineLarge)

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LightsPane.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = pane == i,
                    onClick = {
                        pane = i
                        // 分段=应用模式（互斥选择对灯生效）——直接读 VM 当前值
                        when (p) {
                            LightsPane.STATIC -> {
                                val c = vm.color.value
                                vm.setColor(c.hue, c.sat, send = true)
                            }
                            LightsPane.EFFECT -> {
                                val m = vm.mode.value
                                val cat = (m.selected - 1) / 17
                                val pos = (m.selected - 1) % 17
                                vm.selectEffect(cat, pos, m.selected)
                            }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, LightsPane.entries.size),
                    icon = {}
                ) { Text(p.label) }
            }
        }

        if (pane == LightsPane.STATIC.ordinal) {
            StaticPane(vm)
        } else {
            EffectPane(vm)
        }
    }
}

// ━━━ 单色子视图 ━━━

@Composable
private fun StaticPane(vm: AppViewModel) {
    val color by vm.color.collectAsState()

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HueWheel(
            hue = color.hue,
            onPick = { h -> vm.setColor(h, color.sat, send = false) },
            onPicked = { h -> vm.setColor(h, color.sat, send = true) },
            modifier = Modifier.size(260.dp)
        )
    }

    // 控制条：仅饱和度（D3——亮度在设备页）
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${(color.sat * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            Text("饱和度", style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = color.sat,
            onValueChange = { vm.setColor(color.hue, it, send = false) },
            onValueChangeFinished = { vm.setColor(color.hue, color.sat, send = true) },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "饱和度：当前 ${(color.sat * 100).toInt()}%" }
        )
    }

    Text("预设", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        presets.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p ->
                    PresetDot(
                        preset = p,
                        selected = color.hue == p.hue,
                        modifier = Modifier.weight(1f)
                    ) { vm.setColor(p.hue, p.sat) }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PresetDot(preset: Preset, selected: Boolean, modifier: Modifier, onTap: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(colorOf(preset.hue, preset.sat))
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape
            )
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Button, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 色环：拖动/点按取色；onPick 本地跟手，onPicked 松手发帧 */
@Composable
private fun HueWheel(
    hue: Int,
    onPick: (Int) -> Unit,
    onPicked: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val ringColors = remember {
        mutableListOf<Color>().apply { for (h in 0..360 step 30) add(colorOf(h % 360, 1f)) }
    }
    var lastDrag by remember { mutableStateOf<Offset?>(null) }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "色相环：拖动或点按选取色相"
                    stateDescription = "当前色相 $hue 度"
                }
                .pointerInput(onPick, onPicked) {
                    val strokePx = 34.dp.toPx()
                    val half = strokePx / 2
                    val cx = size.width / 2.0
                    val cy = size.height / 2.0
                    detectTapGestures { off ->
                        pickFrom(off, cx, cy)?.let(onPicked)
                    }
                }
                .pointerInput(onPick, onPicked) {
                    val cx = size.width / 2.0
                    val cy = size.height / 2.0
                    detectDragGestures(
                        onDragEnd = {
                            lastDrag?.let { pickFrom(it, cx, cy)?.let(onPicked) }
                        }
                    ) { change, _ ->
                        change.consume()
                        lastDrag = change.position
                        pickFrom(change.position, cx, cy)?.let(onPick)
                    }
                }
        ) {
            val stroke = 34.dp.toPx()
            drawArc(
                brush = Brush.sweepGradient(ringColors),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke)
            )
            val r = (size.minDimension - stroke) / 2
            val rad = Math.toRadians(hue.toDouble()) // 0°=右（与渐变/拾取同原点）
            val cx = size.width / 2 + r * sin(rad).toFloat()
            val cy = size.height / 2 + r * cos(rad).toFloat()
            drawCircle(Color.White, radius = 17.dp.toPx(), center = Offset(cx, cy))
            drawCircle(colorOf(hue, 1f), radius = 12.dp.toPx(), center = Offset(cx, cy))
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(140.dp)
                .clip(CircleShape)
                .background(colorOf(hue, 1f))
                .border(4.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

/** 位置 → 色相（0°=右顺时针）；环外无效返回 null */
private fun pickFrom(pos: Offset, cx: Double, cy: Double): Int? {
    val dx = pos.x - cx
    val dy = pos.y - cy
    // 中心盘（140dp 直径）与环外都拒绝：命中即近似环带
    val dist = sqrt(dx * dx + dy * dy)
    val maxR = 200.0
    if (dist < 70.0 || dist > maxR) return null
    return hueAt(dx, dy)
}

// ━━━ 灯效子视图 ━━━

// 名称色 → 示意色（客观编码名称中的颜色词；标注"示意"）
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

@Composable
private fun HintBar(name: String, modifier: Modifier) {
    val colors = hintColors(name)
    val hasDot = name.contains("点、")
    Canvas(modifier.height(10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(
            brush = if (colors.size == 1) Brush.horizontalGradient(listOf(colors[0], colors[0]))
            else Brush.horizontalGradient(colors),
            size = size
        )
        if (hasDot) {
            drawCircle(
                Color.White, radius = size.height * 0.55f,
                center = Offset(size.width * 0.25f, size.height / 2)
            )
        }
    }
}

@Composable
private fun EffectPane(vm: AppViewModel) {
    val mode by vm.mode.collectAsState()
    val conn by vm.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(mode.tab) }
    val selectedQuandeng = (mode.selected - 1) / 17 == 8

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 当前效果条
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "当前灯效",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // 有依据的状态（R19-08）：未连接不得伪装"运行中"
                    if (conn == ConnState.CONNECTED) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                "运行中",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HintBar(Effects.nameOf(mode.selected), Modifier.weight(1f))
                    Text("示意", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 分类 pills
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

        // 双列网格（占主空间）
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
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
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(start = 4.dp)
                                )
                            }
                        }
                        HintBar(name, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // 底部紧凑控制条：速度+模式灵敏度两行（无亮度——D3）
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                CompactSlider(
                    label = "速度",
                    note = if (selectedQuandeng) "已选全灯类，上限 70" else null,
                    value = mode.speed,
                    onDrag = { vm.updateSpeed(it) },
                    onRelease = { vm.sendSpeed() }
                )
                CompactSlider(
                    label = "灵敏度",
                    note = null,
                    value = mode.sensitivity,
                    onDrag = { vm.updateSensitivity(it) },
                    onRelease = { vm.sendSensitivity() }
                )
            }
        }
        // context 参考保留（避免未用告警的占位——若后续扩展本页需要 context）
        @Suppress("UNUSED_EXPRESSION")
        context
    }
}

@Composable
private fun CompactSlider(
    label: String,
    note: String?,
    value: Int,
    onDrag: (Int) -> Unit,
    onRelease: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$value",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "  $label${note?.let { "（$it）" } ?: ""}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onDrag(it.toInt()) },
            onValueChangeFinished = onRelease,
            valueRange = 0f..100f
        )
    }
}
