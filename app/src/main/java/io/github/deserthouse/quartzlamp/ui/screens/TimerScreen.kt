package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.ui.AppViewModel

private val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 定时页：开灯/关灯两组（时+分+周重复），状态全部由 VM 槽位持有（两槽互不干扰）。
 * 无"启用"开关——协议无此位（审查 F05）：保存即下发、清空周重存=撤销、读回空=未设置。
 */
@Composable
fun TimerScreen(vm: AppViewModel) {
    val timer by vm.timer.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TimerSection(title = "定时开灯", slot = timer.open, onEdit = { h, m, w ->
            vm.updateTimerSlot(true, h, m, w)
        }, onApply = { vm.saveTimerSlot(true) }, onRead = { vm.queryTimerSlot(true) })
        TimerSection(title = "定时关灯", slot = timer.close, onEdit = { h, m, w ->
            vm.updateTimerSlot(false, h, m, w)
        }, onApply = { vm.saveTimerSlot(false) }, onRead = { vm.queryTimerSlot(false) })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TimerSection(
    title: String,
    slot: AppViewModel.TimerSlot,
    onEdit: (Int, Int, List<Boolean>) -> Unit,
    onApply: () -> Unit,
    onRead: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (slot.busy) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).height(20.dp))
            }
            Text(
                when {
                    slot.error -> "读取失败"
                    !slot.loaded -> "未设置"
                    else -> "已从灯读回"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (slot.error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberPicker(value = slot.hour, range = 0..23, label = "时", onValue = { h ->
                onEdit(h, slot.minute, slot.weeks)
            })
            Text(":", style = MaterialTheme.typography.titleLarge)
            NumberPicker(value = slot.minute, range = 0..59, label = "分", onValue = { m ->
                onEdit(slot.hour, m, slot.weeks)
            })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            weekLabels.forEachIndexed { i, d ->
                FilterChip(
                    selected = slot.weeks[i],
                    onClick = { onEdit(slot.hour, slot.minute, slot.weeks.toMutableList().also { it[i] = !it[i] }) },
                    label = { Text(d) }
                )
            }
        }

        Row {
            TextButton(onClick = onApply) { Text("应用到灯") }
            TextButton(onClick = onRead) { Text("从灯读取") }
        }
    }
}

@Composable
private fun NumberPicker(value: Int, range: IntRange, label: String, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (value > range.first) onValue(value - 1) }) { Text("−") }
        Text(
            "%02d".format(value),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        TextButton(onClick = { if (value < range.last) onValue(value + 1) }) { Text("＋") }
    }
}
