package io.github.deserthouse.quartzlamp.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 石英色板对比度门禁（审查 §6.5 第 4 条，确定性断言替代目测）：
 * 正文对 ≥ 4.5:1；大字/图形对 ≥ 3:1（WCAG 2.1 AA）。
 * W3 契约：改任何 token 前先过这里。
 */
class ContrastTest {

    private fun linear(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }

    private fun ratio(fg: Long, bg: Long): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun c(color: androidx.compose.ui.graphics.Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    private fun assertReadable(fg: Long, bg: Long, min: Double, what: String) {
        val r = ratio(fg, bg)
        assertTrue("$what 对比度 $r < $min", r >= min)
    }

    // ━━━ 浅色 ━━━

    @Test fun `浅色 正文对`() {
        val s = IrisLight
        assertReadable(c(s.onSurface), c(s.surface), 4.5, "浅色 onSurface/surface")
        assertReadable(c(s.onBackground), c(s.background), 4.5, "浅色 onBackground/background")
        assertReadable(c(s.onSurfaceVariant), c(s.surfaceVariant), 4.5, "浅色 onSurfaceVariant/surfaceVariant")
    }

    @Test fun `浅色 按钮与容器对`() {
        val s = IrisLight
        assertReadable(c(s.onPrimary), c(s.primary), 4.5, "浅色 onPrimary/primary")
        assertReadable(c(s.onPrimaryContainer), c(s.primaryContainer), 4.5, "浅色 onPrimaryContainer/primaryContainer")
        assertReadable(c(s.onSecondaryContainer), c(s.secondaryContainer), 4.5, "浅色 onSecondaryContainer/secondaryContainer")
        assertReadable(c(s.onError), c(s.error), 4.5, "浅色 onError/error")
    }

    @Test fun `浅色 大字号与描边对`() {
        val s = IrisLight
        assertReadable(c(s.primary), c(s.surface), 3.0, "浅色 primary/surface（大数值/图标）")
        assertReadable(c(s.outline), c(s.surface), 1.6, "浅色 outline/surface（非文本描边）")
    }

    // ━━━ 深色 ━━━

    @Test fun `深色 正文对`() {
        val s = IrisDark
        assertReadable(c(s.onSurface), c(s.surface), 4.5, "深色 onSurface/surface")
        assertReadable(c(s.onBackground), c(s.background), 4.5, "深色 onBackground/background")
        assertReadable(c(s.onSurfaceVariant), c(s.surfaceVariant), 4.5, "深色 onSurfaceVariant/surfaceVariant")
    }

    @Test fun `深色 按钮与容器对`() {
        val s = IrisDark
        assertReadable(c(s.onPrimary), c(s.primary), 4.5, "深色 onPrimary/primary")
        assertReadable(c(s.onPrimaryContainer), c(s.primaryContainer), 4.5, "深色 onPrimaryContainer/primaryContainer")
        assertReadable(c(s.onSecondaryContainer), c(s.secondaryContainer), 4.5, "深色 onSecondaryContainer/secondaryContainer")
        assertReadable(c(s.onError), c(s.error), 4.5, "深色 onError/error")
    }

    @Test fun `深色 大字号与描边对`() {
        val s = IrisDark
        assertReadable(c(s.primary), c(s.surface), 3.0, "深色 primary/surface（大数值/图标）")
        assertReadable(c(s.outline), c(s.surface), 1.6, "深色 outline/surface（非文本描边）")
    }

    @Test fun `两种色板所有容器上的正文和次要文本均可读`() {
        listOf("浅色" to IrisLight, "深色" to IrisDark).forEach { (name, s) ->
            val containers = listOf(
                s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest
            )
            containers.forEachIndexed { i, background ->
                assertReadable(c(s.onSurface), c(background), 4.5, "$name 容器$i 正文")
                assertReadable(c(s.onSurfaceVariant), c(background), 4.5, "$name 容器$i 次要文字")
            }
            assertReadable(c(s.onSurface), c(s.secondaryContainer), 4.5, "$name 设置选项标题")
            assertReadable(c(s.onSurfaceVariant), c(s.secondaryContainer), 4.5, "$name 设置选项说明")
            assertReadable(c(s.primary), c(s.surfaceContainerLow), 4.5, "$name 应用版本号")
            assertReadable(c(s.onSecondary), c(s.secondary), 4.5, "$name onSecondary")
            assertReadable(c(s.onTertiary), c(s.tertiary), 4.5, "$name onTertiary")
            assertReadable(c(s.onTertiaryContainer), c(s.tertiaryContainer), 4.5, "$name 暖砂容器")
            assertReadable(c(s.inverseOnSurface), c(s.inverseSurface), 4.5, "$name 反色表面")
        }
    }

    @Test fun `容器明度按层级递进且明暗模式方向相反`() {
        listOf(IrisLight, IrisDark).forEach { s ->
            val tones = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest).map { luminance(c(it)) }
            tones.zipWithNext().forEach { (a, b) ->
                assertTrue("容器层次必须可区分", if (s == IrisLight) a > b else a < b)
            }
        }
    }
}
