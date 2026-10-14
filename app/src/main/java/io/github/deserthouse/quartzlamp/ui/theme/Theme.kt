package io.github.deserthouse.quartzlamp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * QuartzLamp Material 3 Expressive 主题。
 * Android 12+ 默认 Material You 动态取色；静态色板走品牌琥珀（灯暖色）。
 */
@Composable
fun QuartzLampTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private val BrandLight = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF6E5B49),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF8DFC8),
    onSecondaryContainer = Color(0xFF26190B),
    tertiary = Color(0xFF4E6540),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD0E7BE),
    onTertiaryContainer = Color(0xFF0C2003),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFFFB868),
    onPrimary = Color(0xFF492900),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFDBC3AB),
    onSecondary = Color(0xFF3C2E1F),
    secondaryContainer = Color(0xFF554433),
    onSecondaryContainer = Color(0xFFF8DFC8),
    tertiary = Color(0xFFB4CDA4),
    onTertiary = Color(0xFF213513),
    tertiaryContainer = Color(0xFF374B28),
    onTertiaryContainer = Color(0xFFD0E7BE),
)
