package io.github.deserthouse.quartzlamp.ui

import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 状态真实性（双源审查 CR-1/CR-2）：
 * 断开复位灯效投递位（重连后新会话从未收到 BC06）；电源开关结果驱动不乐观置位。
 */
class StateTruthfulnessTest {

    private val lamp = TestLampConnection()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = AppViewModel(lamp)

    @Test fun `全部断开复位灯效投递位`() {
        val vm = vm()
        vm.connect(ScannedLamp("A", "AA", -50))
        vm.selectEffect(0, 0, 1) // 投递成功（桩有连接即成功）
        assertTrue(vm.lastEffectSendOk.value)
        lamp.disconnect("AA")
        Thread.sleep(100)
        assertFalse("断开后旧投递证据不得延续", vm.lastEffectSendOk.value)
    }

    @Test fun `双灯断一留一投递位保持`() {
        val vm = vm()
        vm.connect(ScannedLamp("A", "AA", -50))
        vm.connect(ScannedLamp("B", "BB", -51))
        vm.selectEffect(0, 0, 1)
        assertTrue(vm.lastEffectSendOk.value)
        lamp.disconnect("AA")
        Thread.sleep(100)
        assertTrue("仍有已连接灯时投递位不复位", vm.lastEffectSendOk.value)
    }

    @Test fun `未连接时电源不乐观置位`() {
        val vm = vm() // 无任何连接
        assertTrue(vm.powerOn.value)
        vm.setPower(false)
        Thread.sleep(100)
        assertTrue("NoTarget 不得翻转状态型控制", vm.powerOn.value)
    }

    @Test fun `连接时电源正常生效`() {
        val vm = vm()
        vm.connect(ScannedLamp("A", "AA", -50))
        vm.setPower(false)
        Thread.sleep(100)
        assertFalse(vm.powerOn.value)
        val hex = io.github.deserthouse.quartzlamp.protocol.LampFrames.hex(
            io.github.deserthouse.quartzlamp.protocol.LampFrames.power(false)
        )
        assertTrue("投递帧应入桩日志", lamp.sentFrames.contains(hex))
    }
}
