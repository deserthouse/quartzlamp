package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.deserthouse.quartzlamp.ui.AppViewModel

private val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
private val weekFull = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun summarize(slot: AppViewModel.TimerSlot, open: Boolean): String {
    val days = slot.weeks.withIndex().filter { it.value }.map { weekFull[it.index] }
    val action = if (open) "开灯" else "关灯"
    val time = "%02d:%02d".format(slot.hour, slot.minute)
    // 零周位行为未证实（真灯探针 A4），文案用中性"重复：无"而非宣称仅一次
    return if (days.isEmpty()) "重复：无选中日期 · $time $action"
    else "${days.joinToString("·")} $time $action"
}

/**
 * 定时屏（光谱控制台补课，W2）：
 * 两槽卡片=大号 24h 时间（点击弹 TimePicker）+ 周行 + 一句可读摘要 + 目标灯标注；
 * 状态标签（未设置/读取中/已从灯读回/读取失败）。协议逻辑零改动（R2 槽位语义原样）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(vm: AppViewModel) {
    val timer by vm.timer.collectAsState()

    // 进页即对时（厂商 timer.initView 同构，P0 审计补强）——定时的正确性依赖灯内时钟
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.syncClock() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("定时", style = MaterialTheme.typography.headlineLarge)
        TimerCard("定时开灯", timer.open, open = true, vm = vm)
        TimerCard("定时关灯", timer.close, open = false, vm = vm)
        Text(
            "时间配置写入灯端存储（所有已连接灯），断电不丢；读取\"未设置\"即清空该时段",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp)) // W-R2：滚动列尾余量
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerCard(
    title: String,
    slot: AppViewModel.TimerSlot,
    open: Boolean,
    vm: AppViewModel
) {
    var showPicker by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (slot.busy) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp))
                }
                Text(
                    // AV04-03：互斥阶段优先（本次动作结果），历史观测（loaded）次之，不再互相压制
                    when (slot.phase) {
                        AppViewModel.TimerPhase.NoTarget -> "未连接 · 未发送（草稿已保留）"
                        AppViewModel.TimerPhase.Failed -> "发送/读取失败 · 可重试"
                        AppViewModel.TimerPhase.Sent -> when {
                            slot.dirty && slot.partial -> "已发送 · 部分灯未确认，可重试"
                            slot.dirty -> "草稿未应用"
                            else -> "已发送 · 待读回确认"
                        }
                        AppViewModel.TimerPhase.Idle ->
                            if (slot.dirty) "草稿未应用"
                            else if (slot.loaded) "已从灯读回" + (slot.readFrom?.let { "（$it）" } ?: "")
                            else "未设置"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (slot.phase) {
                        AppViewModel.TimerPhase.Failed -> MaterialTheme.colorScheme.error
                        AppViewModel.TimerPhase.NoTarget -> MaterialTheme.colorScheme.tertiary
                        else -> if (slot.dirty || slot.loaded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // 大号时间（点击弹 TimePicker；触区 ≥48dp 由 displayMedium 行高保证）
            Text(
                "%02d:%02d".format(slot.hour, slot.minute),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { showPicker = true }
                    .padding(vertical = 2.dp)
            )

            // 周重复 chips（N3：FlowRow 紧凑单行——常规 7 枚一行，大字自动换行不截字；
            // 禁用 M3 48dp 最小触区强制，否则 41dp 胶囊被撑宽致 6+1 折行）
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                weekLabels.forEachIndexed { i, d ->
                    WeekChip(
                        label = d,
                        selected = slot.weeks[i],
                        onTap = {
                            vm.updateTimerSlot(
                                open, slot.hour, slot.minute,
                                slot.weeks.toMutableList().also { it[i] = !it[i] }
                            )
                        }
                    )
                }
            }

            // 一句可读摘要
            Text(
                summarize(slot, open),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row {
                TextButton(onClick = { vm.saveTimerSlot(open) }) { Text("应用到灯") }
                TextButton(onClick = { vm.queryTimerSlot(open) }) { Text("从灯读取") }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberTimePickerState(
            initialHour = slot.hour, initialMinute = slot.minute, is24Hour = true
        )
        Dialog(
            onDismissRequest = { showPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("选择时间（24 小时制）", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TimePicker(state = pickerState)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPicker = false }) { Text("取消") }
                        TextButton(onClick = {
                            vm.updateTimerSlot(open, pickerState.hour, pickerState.minute, slot.weeks)
                            showPicker = false
                        }) { Text("确定") }
                    }
                }
            }
        }
    }
}

/** 紧凑周 chip（N3）：FilterChip/Surface(onClick) 均被 M3 48dp 最小尺寸撑宽（6+1 折行），
 *  用无 onClick 的 Surface + foundation clickable（无最小尺寸强制）保证 7 枚单行 */
@Composable
private fun WeekChip(label: String, selected: Boolean, onTap: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
                contentDescription = "周${label}${if (selected) "（已选中）" else ""}"
            }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}
