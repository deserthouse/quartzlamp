package io.github.deserthouse.quartzlamp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 律动源切换帧契约（R19-01 关闭条件：手机→灯载→音乐→手机每次源转换有可断言帧序列）。
 */
class RhythmSourceTest {

    private fun vm(): Pair<AppViewModel, TestLampConnection> {
        val lamp = TestLampConnection()
        return AppViewModel(lamp) to lamp
    }

    @Test fun `切到灯载麦发源帧00并重下发灯端配置`() {
        val (vm, lamp) = vm()
        vm.selectRhythmSource(RhythmSource.LAMP_MIC)
        assertEquals(
            listOf("BC0F010055", "BC11010155", "BC12013255"), // 源00 + 类型(默认经典1) + 灵敏度(默认50=0x32)
            lamp.sentFrames.toList()
        )
        assertEquals(RhythmSource.LAMP_MIC, vm.rhythmSource.value)
    }

    @Test fun `灯载切音乐切回手机各发源帧01`() {
        val (vm, lamp) = vm()
        vm.selectRhythmSource(RhythmSource.LAMP_MIC)
        lamp.sentFrames.clear()
        vm.selectRhythmSource(RhythmSource.MUSIC)
        assertEquals(listOf("BC0F010155"), lamp.sentFrames.toList())
        lamp.sentFrames.clear()
        vm.selectRhythmSource(RhythmSource.PHONE_MIC)
        assertEquals(listOf("BC0F010155"), lamp.sentFrames.toList())
    }

    @Test fun `重复选择同源不重发`() {
        val (vm, lamp) = vm()
        vm.selectRhythmSource(RhythmSource.PHONE_MIC) // 默认即手机麦
        assertTrue(lamp.sentFrames.isEmpty())
    }
}
