package io.github.deserthouse.quartzlamp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 光谱控制台形状（审查 §6.2）：主控制大容器 28-40dp；紧密组内 12-20dp；
 * pill 形状由具体组件用 CircleShape 表达（导航/源切换）。
 */
val QuartzShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
