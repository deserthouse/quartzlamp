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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.rememberUpdatedState
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

/** 灯效页子模式（页内分段，N4b 更名：静态=纯色/动态=灯效） */
private enum class LightsPane(val label: String) { STATIC("静态"), EFFECT("动态") }

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
 * 灯效 tab（V 系列合并页，U3/U4；N4b 更名）：`静态|动态` 分段=灯模式互斥选择。
 * 静态=色环+预设+饱和度（无亮度——D3，亮度在设备页）；
 * 动态=控制条+当前效果条+分类+双列网格（限幅跟已选效果）。
 * 历史名："灯光"tab/"单色|灯效"分段（0.4.2 及之前）。
 */
@Composable
fun LightsScreen(vm: AppViewModel) {
    val lampMode by vm.lampMode.collectAsState()
    var pane by remember { mutableIntStateOf(if (lampMode is LampMode.Static) 0 else 1) }

    // N4：标题与分段随内容滚动（不再钉顶）——整页单一滚动流
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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

    // N4：滚动上移至 LightsScreen 外层，本 pane 为流式布局
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HueWheel(
                hue = color.hue,
                currentSat = color.sat,
                onPick = { h -> vm.setColor(h, color.sat, send = false) },
                onPicked = { h -> vm.setColor(h, color.sat, send = false); vm.commitColor() },
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
                // AV04-09：松手不读闭包快照（快速点按会把刚更新的值覆盖回旧值）——
                // commit 从 VM 权威当前值提交，拖/点路径一致
                onValueChangeFinished = { vm.commitColor() },
                valueRange = 0f..1f,
                modifier = Modifier.semantics {
                    contentDescription = "饱和度：当前 ${(color.sat * 100).toInt()}%"
                }
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
        Spacer(Modifier.height(16.dp)) // W-R2：滚动列尾余量
    }
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
            .semantics { contentDescription = "预设颜色 ${preset.name}${if (selected) "，已选中" else ""}" }
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Button, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            // W-R3：亮色预设（黄/白系）白勾不可见——按亮度选墨色勾
            val c = colorOf(preset.hue, preset.sat)
            val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
            Icon(
                Icons.Filled.Check, contentDescription = null,
                tint = if (lum > 0.6f) Color(0xFF1B1B21) else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 色环：拖动/点按取色；onPick 本地跟手，onPicked 松手发帧 */
@Composable
private fun HueWheel(
    hue: Int,
    currentSat: Float,
    onPick: (Int) -> Unit,
    onPicked: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val ringColors = remember {
        mutableListOf<Color>().apply { for (h in 0..360 step 30) add(colorOf(h % 360, 1f)) }
    }
    var lastDrag by remember { mutableStateOf<Offset?>(null) }
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnPicked by rememberUpdatedState(onPicked)

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "色相环：拖动或点按选取色相"
                    stateDescription = "当前色相 $hue 度"
                }
                .pointerInput(Unit) {
                    val strokePx = 34.dp.toPx()
                    val half = strokePx / 2
                    val ringR = min(size.width, size.height) / 2f - half
                    val slack = 12.dp.toPx()
                    val cx = size.width / 2.0
                    val cy = size.height / 2.0
                    detectTapGestures { off ->
                        pickFrom(off, cx, cy, ringR.toDouble(), half.toDouble(), slack.toDouble())?.let(currentOnPicked)
                    }
                }
                .pointerInput(Unit) {
                    val strokePx = 34.dp.toPx()
                    val half = strokePx / 2
                    val ringR = min(size.width, size.height) / 2f - half
                    val slack = 12.dp.toPx()
                    val cx = size.width / 2.0
                    val cy = size.height / 2.0
                    detectDragGestures(
                        onDragCancel = { lastDrag = null }, // AV04 自首：取消不提交陈旧值
                        onDragEnd = {
                            lastDrag?.let {
                                pickFrom(it, cx, cy, ringR.toDouble(), half.toDouble(), slack.toDouble())?.let(currentOnPicked)
                            }
                            lastDrag = null
                        }
                    ) { change, _ ->
                        change.consume()
                        lastDrag = change.position
                        pickFrom(change.position, cx, cy, ringR.toDouble(), half.toDouble(), slack.toDouble())?.let(currentOnPick)
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
            val cx = size.width / 2 + r * cos(rad).toFloat()
            val cy = size.height / 2 + r * sin(rad).toFloat()
            drawCircle(Color.White, radius = 17.dp.toPx(), center = Offset(cx, cy))
            drawCircle(colorOf(hue, 1f), radius = 12.dp.toPx(), center = Offset(cx, cy))
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(140.dp)
                .clip(CircleShape)
                .background(colorOf(hue, currentSat))
                .border(4.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

/**
 * 位置 → 色相（0°=右顺时针）。环带几何由调用方按密度换算传入（ringR/halfStroke/slack，
 * 勿用硬编码像素——260dp 环带实际在 240-360px@2.625x，魔法数阈值会把"点环"全部拒掉）。
 * 中心盘与环外返回 null。
 */
private fun pickFrom(pos: Offset, cx: Double, cy: Double, ringR: Double, halfStroke: Double, slack: Double): Int? {
    val dx = pos.x - cx
    val dy = pos.y - cy
    val dist = sqrt(dx * dx + dy * dy)
    if (!inRingBand(dist, ringR, halfStroke, slack)) return null
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
private fun HintBar(name: String, tab: Int, modifier: Modifier) {
    // N7：按二级分类校准——点前缀色从渐变中剥离为独立点；双色=底色+前景块（无渐变）；
    // 坏项（纯色）单色静态=真机行为的准确展示
    val dotPrefix = Effects.dotPrefixOf(name)
    val baseName = if (dotPrefix != null) name.removePrefix(dotPrefix) else name
    val dotColor = when {
        dotPrefix?.startsWith("白点") == true -> singleColorMap['白']!!
        dotPrefix?.startsWith("红点") == true -> singleColorMap['红']!!
        else -> null
    }
    val cat = Effects.subCatOf(tab, name)
    val colors = hintColors(baseName)
    val outlineColor = MaterialTheme.colorScheme.outline
    Canvas(modifier.height(10.dp).clip(RoundedCornerShape(5.dp))) {
        when (cat) {
            Effects.SubCat.DUO -> {
                drawRect(colors[0]) // 底色满幅
                if (colors.size > 1) {
                    val blockW = size.width * 0.45f
                    val topLeft = Offset((size.width - blockW) / 2f, 0f)
                    val blockSize = Size(blockW, size.height)
                    drawRect(colors[1], topLeft = topLeft, size = blockSize)
                    // W-R3：近白前景块加描边（浅色卡底上块形可辨）
                    val lum = 0.299f * colors[1].red + 0.587f * colors[1].green + 0.114f * colors[1].blue
                    if (lum > 0.8f) {
                        drawRect(
                            color = outlineColor,
                            style = Stroke(1.dp.toPx()),
                            topLeft = topLeft,
                            size = blockSize
                        )
                    }
                }
            }
            Effects.SubCat.RAINBOW -> drawRect(Brush.horizontalGradient(rainbow))
            Effects.SubCat.MONO_GRAD, Effects.SubCat.COLOR_GRAD ->
                drawRect(
                    brush = if (colors.size == 1) Brush.horizontalGradient(listOf(colors[0], colors[0]))
                    else Brush.horizontalGradient(colors),
                    size = size
                )
            else -> drawRect(colors[0]) // 单色/纯色（坏）：名称单色静态
        }
        dotColor?.let { dc ->
            drawCircle(dc, radius = size.height * 0.32f, center = Offset(size.width * 0.22f, size.height / 2))
        }
    }
}

@Composable
private fun EffectPane(vm: AppViewModel) {
    val mode by vm.mode.collectAsState()
    val conn by vm.state.collectAsState()
    val running by vm.effectRunning.collectAsState()
    val powerOn by vm.powerOn.collectAsState()
    var tab by remember { mutableIntStateOf(mode.tab) }
    val selectedQuandeng = (mode.selected - 1) / 17 == 8

    // N4：滚动上移至 LightsScreen 外层；控制条上移至分段正下方（用户意见），
    // 其后=当前效果条→分类→网格
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 速度+模式灵敏度控制条（分段下方；U2 并排方案实测标签重叠已撤回——
        // CompactSlider 非半宽设计，堆叠为正确形态）
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp) // UI51-01：两组间显式组距
            ) {
                CompactSlider(
                    label = "速度",
                    note = if (selectedQuandeng) "已选全灯类，上限 70" else null,
                    // AV04-04：显示发送值——限幅时 UI 与帧一致
                    value = if (selectedQuandeng) mode.speed.coerceAtMost(70) else mode.speed,
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

        // 紧凑当前效果条（单行，高度 ~56dp——不再挤占主选择区）
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Effects.nameOf(mode.selected),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HintBar(Effects.nameOf(mode.selected), tab, Modifier.weight(1f))
                        Text("示意", style = MaterialTheme.typography.labelMedium)
                    }
                }
                // S1/R44-04：运行中=投递全成功+成员一致（组合流），不再读折叠布尔
                Surface(
                    shape = CircleShape,
                    color = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    contentColor = if (running) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        when {
                            running -> "运行中"
                            conn != ConnState.CONNECTED -> "未连接"
                            !powerOn -> "已关灯"
                            else -> "已选择"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // 分类 pills（V44-01 修正：尾缘 24dp 渐隐仅在有更多内容时出现——
        // W-R2 的全宽 matchParentSize 遮罩会把次枚画成禁用态）
        val pillScroll = rememberScrollState()
        Box {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(pillScroll)
        ) {
            Effects.categories.forEachIndexed { i, name ->
                val selected = tab == i
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { tab = i }
                        )
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }
        // 尾缘渐隐：canScrollForward 时画尾侧 24dp；头侧渐隐独立按 canScrollBackward（UI51-05）
        if (pillScroll.canScrollForward) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(24.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.background
                            )
                        )
                )
            }
        }
        if (pillScroll.canScrollBackward) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(24.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                0f to MaterialTheme.colorScheme.background,
                                1f to Color.Transparent
                            )
                        )
                )
            }
        }
        }

        // 双列网格（N5 分组 + UI51-06 selectableGroup 单选组语义）
        // 单选语义不能改变测量布局：所有分组与卡片行必须依次占据纵向空间。
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        val items = Effects.effects[tab]
        // U2：大字下长名唯一区分词不被两行省略号吞掉——fontScale>1.3 切单列
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val cols = if (fontScale > 1.3f) 1 else 2
        val groups = Effects.SUB_CAT_ORDER.map { c ->
            c to items.withIndex().filter { Effects.subCatOf(tab, it.value) == c }
        }.filter { it.second.isNotEmpty() }
        groups.forEachIndexed { gi, (cat, members) ->
            Text(
                Effects.subCatLabel(cat),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (gi == 0) 0.dp else 4.dp)
            )
            if (cat == Effects.SubCat.PURE_BROKEN) {
                Text(
                    "以下效果在部分设备上仅显示为纯色（原厂固件自带行为，功能保留）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            members.chunked(cols).forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowItems.forEach { (pos, name) ->
                        val deviceIndex = Effects.deviceIndex(tab, pos)
                        val selected = mode.selected == deviceIndex
                        EffectCard(
                            name = name,
                            selected = selected,
                            tab = tab,
                            modifier = Modifier.weight(1f)
                        ) { vm.selectEffect(tab, pos, deviceIndex) }
                    }
                    if (rowItems.size == 1 && cols == 2) Spacer(Modifier.weight(1f)) // 奇数行占位对齐
                }
            }
        }
        }

        Spacer(Modifier.height(16.dp)) // W-R2：滚动列尾余量（底栏呼吸，四页统一）
    }
}

@Composable
private fun EffectCard(name: String, selected: Boolean, tab: Int, modifier: Modifier, onTap: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(vertical = 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.medium
            )
            .selectable(
                selected = selected,
                role = androidx.compose.ui.semantics.Role.RadioButton,
                onClick = onTap
            )
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
            HintBar(name, tab, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CompactSlider(
    label: String,
    note: String?,
    value: Int,
    onDrag: (Int) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    // UI51-01：标签/数值行与轨道 6dp 独立间隔；限幅说明独立行不与标签拼接；
    // Slider 绑定 label+stateDescription（读屏可区分两个控制）
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$value",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "  $label",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onDrag(it.toInt()) },
            onValueChangeFinished = onRelease,
            valueRange = 0f..100f,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = "$label $value"
            }
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
