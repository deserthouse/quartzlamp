package io.github.deserthouse.quartzlamp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色环几何契约（R19-06 关闭条件：四方位可见色=拾取值）。
 * 原点=正右（3 点钟）顺时针，与 Brush.sweepGradient 色序一致（0°=红）。
 */
class HueMathTest {

    @Test fun `四方位角度`() {
        assertEquals(0, hueAt(1.0, 0.0))    // 右=红(H0)
        assertEquals(90, hueAt(0.0, 1.0))   // 下（屏幕 y 向下=顺时针 90°）
        assertEquals(180, hueAt(-1.0, 0.0)) // 左
        assertEquals(270, hueAt(0.0, -1.0)) // 上
    }

    @Test fun `负角归一`() {
        assertEquals(350, hueAt(Math.cos(Math.toRadians(-10.0)), Math.sin(Math.toRadians(-10.0))))
        assertEquals(359, hueAt(Math.cos(Math.toRadians(-1.0)), Math.sin(Math.toRadians(-1.0))))
        assertEquals(0, hueAt(Math.cos(Math.toRadians(0.0)), Math.sin(Math.toRadians(0.0))))
    }

    @Test fun `环带命中`() {
        assertTrue(inRingBand(100.0, 100.0, 17.0))              // 正环心
        assertTrue(inRingBand(125.0, 100.0, 17.0, 10.0))        // 容差内
        assertFalse(inRingBand(0.0, 100.0, 17.0, 10.0))         // 中心色盘：拒绝
        assertFalse(inRingBand(200.0, 100.0, 17.0, 10.0))       // 环外：拒绝
    }
}
