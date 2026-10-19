package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.ui.AppViewModel
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 预设色（8 色语义保留；2×4 自适应网格——436dp 溢出裁切不可复现，审查 §6.1） */
internal data class Preset(val hue: Int, val sat: Float, val name: String)

/** 预设色（R19-08：带色名语义，无障碍读"预设 红"而非无名色块） */
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

private fun colorOf(h: Int, s: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s.coerceIn(0f, 1f), 1f)))

/**
 * 调色屏（光谱控制台样张，审查 §6.3）：
 * 电源为明显主操作；色环为视觉主角（拖动跟手：本地逐帧、松手发帧）；
 * 亮度有大数值与厚轨道；饱和度进精细调节；预设 2×4 自适应不裁切。
 */
@Composable
fun ColorScreen(vm: AppViewModel) {
    val color by vm.color.collectAsState()
    val powerOn by vm.powerOn.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ━━━ 页头：主标题 + 电源主操作 ━━━
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("调光", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Surface(
                shape = CircleShape,
                color = if (powerOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (powerOn) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .clickable { vm.setPower(!powerOn) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PowerSettingsNew, contentDescription = "电源",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // ━━━ 色环（视觉主角）━━━
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HueWheel(
                hue = color.hue,
                onPick = { h -> vm.setColor(h, color.sat, send = false) },
                onPicked = { h -> vm.setColor(h, color.sat, send = true) },
                modifier = Modifier.size(270.dp)
            )
        }

        // ━━━ 亮度：大数值 + 厚轨道 ━━━
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${color.bright}",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text("亮度", style = MaterialTheme.typography.titleMedium)
            }
            ThickSlider(
                value = color.bright.toFloat(),
                range = 0f..1000f,
                trackBrush = Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceVariant, colorOf(color.hue, color.sat))
                ),
                onValueChange = { vm.updateBrightness(it.toInt()) },
                onValueChangeFinished = { vm.sendBrightness() }
            )
        }

        // ━━━ 饱和度：精细调节 ━━━
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${(color.sat * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                Text("饱和度", style = MaterialTheme.typography.titleMedium)
            }
            ThickSlider(
                value = color.sat,
                range = 0f..1f,
                trackBrush = Brush.horizontalGradient(
                    listOf(colorOf(color.hue, 0.1f), colorOf(color.hue, 1f))
                ),
                onValueChange = { vm.setColor(color.hue, it, send = false) },
                onValueChangeFinished = { vm.setColor(color.hue, color.sat, send = true) }
            )
        }

        // ━━━ 预设：2×4 自适应 ━━━
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
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PresetDot(preset: Preset, selected: Boolean, modifier: Modifier, onTap: () -> Unit) {
    // 选中=描边环+对勾（颜色不独自承担选中含义）；selectable 提供无障碍选中语义（R19-08）
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
            .selectable(selected = selected, role = Role.Button, onClick = onTap),
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

/** 厚轨道滑杆：自定义 thumb/track（可视+交互一体，松手单帧节律由调用方控制） */
@Composable
private fun ThickSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    trackBrush: Brush,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = range,
        thumb = {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
            )
        },
        track = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(trackBrush)
                )
            }
        }
    )
}

/** 色环：拖动/点按取色（0° 顶部顺时针）；onPick 本地跟手，onPicked 松手发帧 */
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
                    stateDescription = "当前色相 ${hue} 度"
                }
                .pointerInput(onPick, onPicked) {
                    val strokePx = 34.dp.toPx()
                    val half = strokePx / 2
                    val ringR = min(size.width, size.height) / 2f - half
                    val slack = 10.dp.toPx()
                    detectTapGestures { off -> pickFrom(off, ringR.toDouble(), half.toDouble(), slack.toDouble())?.let(onPicked) }
                }
                .pointerInput(onPick, onPicked) {
                    val strokePx = 34.dp.toPx()
                    val half = strokePx / 2
                    val ringR = min(size.width, size.height) / 2f - half
                    val slack = 10.dp.toPx()
                    detectDragGestures(
                        onDragEnd = {
                            lastDrag?.let {
                                pickFrom(it, ringR.toDouble(), half.toDouble(), slack.toDouble())?.let(onPicked)
                            }
                        }
                    ) { change, _ ->
                        change.consume()
                        lastDrag = change.position
                        pickFrom(change.position, ringR.toDouble(), half.toDouble(), slack.toDouble())?.let(onPick)
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
            val rad = Math.toRadians(hue.toDouble()) // 0°=右（与 sweepGradient/拾取同原点，R19-06）
            val cx = size.width / 2 + r * sin(rad).toFloat()
            val cy = size.height / 2 + r * cos(rad).toFloat()
            drawCircle(Color.White, radius = 17.dp.toPx(), center = Offset(cx, cy))
            drawCircle(colorOf(hue, 1f), radius = 12.dp.toPx(), center = Offset(cx, cy))
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(148.dp)
                .clip(CircleShape)
                .background(colorOf(hue, 1f))
                .border(4.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

/**
 * 相对圆心的角度 → 色相（R19-06 修复）：**0°=正右（3 点钟）顺时针**——与 Brush.sweepGradient
 * 的色序原点一致（此前绘制用右原点、拾取/指示器用顶原点，恒差 90°：点红色区域发 H=90）。
 */
internal fun hueAt(dx: Double, dy: Double): Int {
    val deg = Math.toDegrees(atan2(dy, dx))
    return ((deg % 360.0 + 360.0) % 360.0).toInt()
}

/** 环带命中（R19-06）：仅色环带可拾取——中心色盘与环外区域无效（此前全盘可命中） */
internal fun inRingBand(dist: Double, ringR: Double, halfStroke: Double, slack: Double = 0.0): Boolean =
    kotlin.math.abs(dist - ringR) <= halfStroke + slack

private fun pickFrom(pos: Offset, ringR: Double, halfStroke: Double, slack: Double): Int? {
    val dx = (pos.x - ringR - halfStroke).toDouble() // canvas 尺寸 = 2*(ringR+halfStroke)
    val dy = (pos.y - ringR - halfStroke).toDouble()
    val dist = sqrt(dx * dx + dy * dy)
    if (!inRingBand(dist, ringR, halfStroke, slack)) return null
    return hueAt(dx, dy)
}
