package io.github.deserthouse.quartzlamp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 光谱控制台字阶（审查 §6.2）：
 * 页面主标题 32sp / 核心数值 40sp / 组标题 18-22sp / 正文 14-16sp / 注释 ≥12sp。
 * 主控制靠字号与字重建立层级，不靠所有文字同时加粗。
 */
val QuartzTypography = Typography(
    displayMedium = TextStyle( // 核心数值（亮度/时间）
        fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold
    ),
    displaySmall = TextStyle(
        fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold
    ),
    headlineLarge = TextStyle( // 页面主标题
        fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle( // 组标题（大）
        fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle( // 组标题
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle( // 正文
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle( // 次要正文
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle( // 注释（≥12sp 下限）
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium
    )
)
