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
 * 石英色板：雾紫强调、中性矿物底色、暖砂第三色。
 * 显式定义容器与固定强调角色，避免自定义主色混入库默认容器色。
 * 动态色仍由系统生成，深浅模式均跟随系统。
 */

// ━━━ 石英品牌色板 ━━━
internal val IrisLight = lightColorScheme(
    primary = Color(0xFF66558F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF25143F),
    secondary = Color(0xFF625B70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF5),
    onSecondaryContainer = Color(0xFF201A2D),
    tertiary = Color(0xFF7A5932),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCB5),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFFCFAFD),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFCFAFD),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceDim = Color(0xFFDDD9E0),
    surfaceBright = Color(0xFFFCFAFD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2F7),
    surfaceContainer = Color(0xFFEFECF2),
    surfaceContainerHigh = Color(0xFFE9E6EC),
    surfaceContainerHighest = Color(0xFFE3E0E6),
    surfaceTint = Color(0xFF66558F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCBC5D0),
    inverseSurface = Color(0xFF323036),
    inverseOnSurface = Color(0xFFF5F2F7),
    inversePrimary = Color(0xFFD0BCF5),
    primaryFixed = Color(0xFFEBDDFF),
    primaryFixedDim = Color(0xFFD0BCF5),
    onPrimaryFixed = Color(0xFF25143F),
    onPrimaryFixedVariant = Color(0xFF4F3D69),
    secondaryFixed = Color(0xFFE8DEF5),
    secondaryFixedDim = Color(0xFFCDC2DB),
    onSecondaryFixed = Color(0xFF201A2D),
    onSecondaryFixedVariant = Color(0xFF4A4358),
    tertiaryFixed = Color(0xFFFFDCB5),
    tertiaryFixedDim = Color(0xFFECC095),
    onTertiaryFixed = Color(0xFF2C1700),
    onTertiaryFixedVariant = Color(0xFF5F4120),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black
)

internal val IrisDark = darkColorScheme(
    primary = Color(0xFFD0BCF5),
    onPrimary = Color(0xFF382751),
    primaryContainer = Color(0xFF4F3D69),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4358),
    onSecondaryContainer = Color(0xFFE8DEF5),
    tertiary = Color(0xFFECC095),
    onTertiary = Color(0xFF452B0C),
    tertiaryContainer = Color(0xFF5F4120),
    onTertiaryContainer = Color(0xFFFFDCB5),
    background = Color(0xFF141217),
    onBackground = Color(0xFFE7E0E9),
    surface = Color(0xFF141217),
    onSurface = Color(0xFFE7E0E9),
    surfaceVariant = Color(0xFF49434F),
    onSurfaceVariant = Color(0xFFCBC5D0),
    surfaceDim = Color(0xFF141217),
    surfaceBright = Color(0xFF3B383F),
    surfaceContainerLowest = Color(0xFF0F0D12),
    surfaceContainerLow = Color(0xFF1D1A20),
    surfaceContainer = Color(0xFF211E24),
    surfaceContainerHigh = Color(0xFF2B282F),
    surfaceContainerHighest = Color(0xFF36323A),
    surfaceTint = Color(0xFFD0BCF5),
    outline = Color(0xFF958F9B),
    outlineVariant = Color(0xFF49434F),
    inverseSurface = Color(0xFFE7E0E9),
    inverseOnSurface = Color(0xFF323036),
    inversePrimary = Color(0xFF66558F),
    primaryFixed = Color(0xFFEBDDFF),
    primaryFixedDim = Color(0xFFD0BCF5),
    onPrimaryFixed = Color(0xFF25143F),
    onPrimaryFixedVariant = Color(0xFF4F3D69),
    secondaryFixed = Color(0xFFE8DEF5),
    secondaryFixedDim = Color(0xFFCDC2DB),
    onSecondaryFixed = Color(0xFF201A2D),
    onSecondaryFixedVariant = Color(0xFF4A4358),
    tertiaryFixed = Color(0xFFFFDCB5),
    tertiaryFixedDim = Color(0xFFECC095),
    onTertiaryFixed = Color(0xFF2C1700),
    onTertiaryFixedVariant = Color(0xFF5F4120),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black
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
