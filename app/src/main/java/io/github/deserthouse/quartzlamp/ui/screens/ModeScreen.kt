package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.data.Effects
import io.github.deserthouse.quartzlamp.ui.AppViewModel

/** 模式页：9 分类 tab × 17 效果网格 + 速度/灵敏度 */
@Composable
fun ModeScreen(vm: AppViewModel) {
    val mode by vm.mode.collectAsState()
    var tab by remember { mutableIntStateOf(mode.tab) }
    val quandeng = tab == 8 // 全灯滚动 = isInQuandengMode（速度下限 70）

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 分类 chips（横向滚动——9 类一行放不下）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Effects.categories.forEachIndexed { i, name ->
                FilterChip(
                    selected = tab == i,
                    onClick = { tab = i },
                    label = { Text(name) }
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(Effects.effects[tab]) { pos, name ->
                val deviceIndex = Effects.deviceIndex(tab, pos)
                FilterChip(
                    selected = mode.selected == deviceIndex,
                    onClick = { vm.selectEffect(tab, pos, deviceIndex) },
                    label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Text("速度 ${mode.speed}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = mode.speed.toFloat(),
            onValueChange = { vm.setSpeed(it.toInt(), quandeng) },
            valueRange = 0f..100f
        )
        Text("灵敏度 ${mode.sensitivity}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = mode.sensitivity.toFloat(),
            onValueChange = { vm.setSensitivity(it.toInt()) },
            valueRange = 0f..100f
        )
    }
}
