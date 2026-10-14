package io.github.deserthouse.quartzlamp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.deserthouse.quartzlamp.ui.AppViewModel

/** 调色页：电源开关 + 色相/饱和度滑杆 + 预设色板 + 亮度 */
@Composable
fun ColorScreen(vm: AppViewModel) {
    val color by vm.color.collectAsState()
    val powerOn by vm.powerOn.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("电源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = powerOn, onCheckedChange = { vm.setPower(it) })
        }

        // 当前颜色预览
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(currentColor(color.hue, color.sat), MaterialTheme.shapes.extraLarge)
        )

        Text("色相 ${color.hue}°", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = color.hue.toFloat(),
            onValueChange = { vm.setColor(it.toInt(), color.sat, send = false) },
            onValueChangeFinished = { vm.setColor(color.hue, color.sat) },
            valueRange = 0f..359f
        )

        Text("饱和度 ${(color.sat * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = color.sat,
            onValueChange = { vm.setColor(color.hue, it, send = false) },
            onValueChangeFinished = { vm.setColor(color.hue, color.sat) },
            valueRange = 0f..1f
        )

        Text("预设", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            presets.forEach { (h, s) ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(currentColor(h, s), CircleShape)
                        .border(
                            if (color.hue == h) 2.dp else 0.dp,
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                        .clickable { vm.setColor(h, s) }
                )
            }
        }

        Text("亮度 ${color.bright}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = color.bright.toFloat(),
            onValueChange = { vm.updateBrightness(it.toInt()) },
            onValueChangeFinished = { vm.sendBrightness() },
            valueRange = 0f..1000f
        )
        Spacer(Modifier.height(8.dp))
    }
}

private val presets = listOf(
    0 to 1f,        // 红
    35 to 1f,       // 橙
    60 to 1f,       // 黄
    120 to 1f,      // 绿
    180 to 1f,      // 青
    210 to 1f,      // 蓝
    265 to 1f,      // 紫
    300 to 1f,      // 品红
)

private fun currentColor(h: Int, s: Float): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s, 1f)))
