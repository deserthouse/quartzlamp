package io.github.deserthouse.quartzlamp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * QuartzLamp 主题（光谱控制台，审查 §6.2）：
 * - 石英静态色板 = 默认（鸢尾紫主色 + 克制琥珀强调），品牌可见；
 * - 动态色 = 用户可选（D1 出设置项），两种共享完整角色色板结构。
 * 浅色为低彩度暖紫白，深色为带紫的深炭色。
 */

// ━━━ 石英品牌色板 ━━━
private val IrisLight = lightColorScheme(
    primary = Color(0xFF4C4EC9),            // 鸢尾紫
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E1FF),
    onPrimaryContainer = Color(0xFF0A0E63),
    secondary = Color(0xFF7A5900),          // 琥珀（克制强调）
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE0A6),
    onSecondaryContainer = Color(0xFF251A00),
    tertiary = Color(0xFF5C5C91),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E0FF),
    onTertiaryContainer = Color(0xFF17173F),
    background = Color(0xFFFDF8FF),         // 暖紫白
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

private val IrisDark = darkColorScheme(
    primary = Color(0xFFBFC3FF),
    onPrimary = Color(0xFF1A1B70),
    primaryContainer = Color(0xFF3333A6),
    onPrimaryContainer = Color(0xFFE2E1FF),
    secondary = Color(0xFFF0C14C),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5B4300),
    onSecondaryContainer = Color(0xFFFFE0A6),
    tertiary = Color(0xFFC5C3FF),
    onTertiary = Color(0xFF2C2B52),
    tertiaryContainer = Color(0xFF434370),
    onTertiaryContainer = Color(0xFFE2E0FF),
    background = Color(0xFF131318),         // 带紫深炭
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF1B1B21),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF2A2832),
    onSurfaceVariant = Color(0xFFC8C5D0),
    outline = Color(0xFF928F9A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun QuartzLampTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // 石英色板为默认品牌表达；动态色为用户可选（D1 设置项）
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> IrisDark
        else -> IrisLight
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = QuartzShapes,
        typography = QuartzTypography,
        content = content
    )
}
