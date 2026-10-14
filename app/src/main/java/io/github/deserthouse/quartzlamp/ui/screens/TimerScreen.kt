package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.ui.AppViewModel

private val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/** 定时页：开灯/关灯两组（时+分+周重复+启用），支持从灯端读回 */
@Composable
fun TimerScreen(vm: AppViewModel) {
    val timer by vm.timer.collectAsState()
    val readTick by vm.timerReadTick.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TimerSection(
            title = "定时开灯",
            hour = timer.openHour,
            minute = timer.openMinute,
            enabled = timer.openEnabled,
            weeks = timer.openWeeks,
            readTick = readTick,
            onApply = vm::updateTimerOpen,
            onRead = { vm.queryTimer(true) {} }
        )
        TimerSection(
            title = "定时关灯",
            hour = timer.closeHour,
            minute = timer.closeMinute,
            enabled = timer.closeEnabled,
            weeks = timer.closeWeeks,
            readTick = readTick,
            onApply = vm::updateTimerClose,
            onRead = { vm.queryTimer(false) {} }
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TimerSection(
    title: String,
    hour: Int,
    minute: Int,
    enabled: Boolean,
    weeks: List<Boolean>,
    readTick: Int,
    onApply: (Int, Int, Boolean, List<Boolean>) -> Unit,
    onRead: () -> Unit
) {
    // readTick 变化 = 从灯读回成功 → 用 VM 回填值重置本地编辑态
    var h by remember(readTick) { mutableIntStateOf(hour) }
    var m by remember(readTick) { mutableIntStateOf(minute) }
    var en by remember(readTick) { mutableStateOf(enabled) }
    var wk by remember(readTick) { mutableStateOf(weeks) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = en, onCheckedChange = { en = it })
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberPicker(value = h, range = 0..23, label = "时", onValue = { h = it })
            Text(":", style = MaterialTheme.typography.titleLarge)
            NumberPicker(value = m, range = 0..59, label = "分", onValue = { m = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            weekLabels.forEachIndexed { i, d ->
                FilterChip(
                    selected = wk[i],
                    onClick = { wk = wk.toMutableList().also { it[i] = !it[i] } },
                    label = { Text(d) }
                )
            }
        }

        Row {
            TextButton(onClick = { onApply(h, m, en, wk) }) { Text("应用到灯") }
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
