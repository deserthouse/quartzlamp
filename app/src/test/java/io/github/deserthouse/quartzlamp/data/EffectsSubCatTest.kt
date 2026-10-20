package io.github.deserthouse.quartzlamp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N5 tab 内二级分类（用户分类法）全量断言：
 * 分布（Python 规则引擎 153 项验证结论）+ tab8 坏项名单 + 索引映射回归。
 */
class EffectsSubCatTest {

    private fun counts(tab: Int) =
        Effects.effects[tab].groupingBy { Effects.subCatOf(tab, it) }.eachCount()

    @Test fun `无点前缀类分布 单色3_单渐6_彩渐4_七彩1_双色3`() {
        for (tab in intArrayOf(0, 1, 4)) {
            assertEquals(
                "tab$tab",
                mapOf(
                    Effects.SubCat.MONO to 3,
                    Effects.SubCat.MONO_GRAD to 6,
                    Effects.SubCat.COLOR_GRAD to 4,
                    Effects.SubCat.RAINBOW to 1,
                    Effects.SubCat.DUO to 3
                ),
                counts(tab)
            )
        }
    }

    @Test fun `点前缀类分布 单色4_单渐5_彩渐4_七彩1_双色3`() {
        // 点前缀类厂商把紫白色板位换成纯紫，故单色多 1/单色渐变少 1
        for (tab in intArrayOf(2, 3, 5, 6, 7)) {
            assertEquals(
                "tab$tab",
                mapOf(
                    Effects.SubCat.MONO to 4,
                    Effects.SubCat.MONO_GRAD to 5,
                    Effects.SubCat.COLOR_GRAD to 4,
                    Effects.SubCat.RAINBOW to 1,
                    Effects.SubCat.DUO to 3
                ),
                counts(tab)
            )
        }
    }

    @Test fun `tab8 坏项恰 6 个且名单逐项断言`() {
        val broken = Effects.effects[8].filter { Effects.subCatOf(8, it) == Effects.SubCat.PURE_BROKEN }
        assertEquals(
            listOf("紫色", "黄色", "红色", "蓝底黄色", "蓝底绿色", "蓝底白色").sorted(),
            broken.sorted()
        )
        // tab8 非坏项：蓝绿等仍为正常彩色渐变/单色渐变/七彩
        assertEquals(Effects.SubCat.COLOR_GRAD, Effects.subCatOf(8, "蓝绿"))
        assertEquals(Effects.SubCat.MONO_GRAD, Effects.subCatOf(8, "蓝白"))
        assertEquals(Effects.SubCat.RAINBOW, Effects.subCatOf(8, "七彩流水"))
    }

    @Test fun `同名原子在非8类不判坏`() {
        // tab0-7 的单色/双色词根是正常动态效果（用户实证）
        assertEquals(Effects.SubCat.MONO, Effects.subCatOf(0, "紫色跳动"))
        assertEquals(Effects.SubCat.MONO, Effects.subCatOf(2, "白点、黄色跳动"))
        assertEquals(Effects.SubCat.DUO, Effects.subCatOf(4, "蓝底黄色双向跳动"))
    }

    @Test fun `分类引擎零漏网_153全归类`() {
        val all = (0..8).flatMap { t -> Effects.effects[t].map { Effects.subCatOf(t, it) } }
        assertEquals(153, all.size)
        assertTrue(all.all { it in Effects.SubCat.entries })
    }

    @Test fun `索引映射不受分组影响`() {
        // nameOf/deviceIndex 往返（分组只重排显示，帧值=固有索引）
        for (idx in 1..Effects.TOTAL) {
            val tab = (idx - 1) / 17
            val pos = (idx - 1) % 17
            assertEquals(idx, Effects.deviceIndex(tab, pos))
            assertEquals(Effects.nameOf(idx), Effects.effects[tab][pos])
        }
        assertEquals("蓝绿跳动", Effects.nameOf(1))
        assertEquals("蓝底白色", Effects.nameOf(153))
    }
}
