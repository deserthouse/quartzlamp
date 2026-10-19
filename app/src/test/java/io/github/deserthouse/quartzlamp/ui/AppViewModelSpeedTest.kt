package io.github.deserthouse.quartzlamp.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 速度限幅跟**已选效果**（R19-10.5 拍板）：浏览 tab 不参与限幅判定——
 * 选中普通效果时浏览全灯类不限幅；选中全灯类效果时无论浏览哪类都限幅。
 */
class AppViewModelSpeedTest {

    private fun vm(): Pair<AppViewModel, TestLampConnection> {
        val lamp = TestLampConnection()
        return AppViewModel(lamp) to lamp
    }

    @Test fun `已选全灯类效果-速度压到70`() {
        val (vm, lamp) = vm()
        vm.selectEffect(8, 0, deviceIndex = 137) // 全灯滚动类
        vm.updateSpeed(90)
        vm.sendSpeed()
        assertTrue(lamp.sentFrames.contains("BC08014655")) // min(90,70)=70=0x46
    }

    @Test fun `已选普通效果-不限幅`() {
        val (vm, lamp) = vm()
        vm.selectEffect(0, 0, deviceIndex = 1)
        vm.updateSpeed(90)
        vm.sendSpeed()
        assertTrue(lamp.sentFrames.contains("BC08015A55")) // 90=0x5A
    }

    @Test fun `浏览全灯类但已选普通效果-不误限`() {
        val (vm, lamp) = vm()
        vm.selectEffect(0, 0, deviceIndex = 1)
        vm.updateSpeed(90)
        vm.sendSpeed() // 即便此刻浏览 tab=8（VM 无浏览概念），已选=普通类 → 不限
        assertTrue(lamp.sentFrames.contains("BC08015A55"))
    }
}
