package io.github.deserthouse.quartzlamp.ui

import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    // ━━━ S1 逐灯投递（R44-04）━━━

    @Test fun `S1_成员替换后运行徽章回落`() {
        val vm = vm()
        val collector = CoroutineScope(Job() + Dispatchers.Default).launch {
            vm.effectRunning.collect {}
        }
        Thread.sleep(100) // 组合流需活跃收集者（WhileSubscribed）
        vm.connect(ScannedLamp("A", "AA", -50))
        vm.selectEffect(0, 0, 1)
        assertTrue(vm.lastEffectSendOk.value)
        vm.connect(ScannedLamp("B", "BB", -51))
        lamp.disconnect("AA")
        Thread.sleep(200)
        assertTrue("成员替换后旧投递证据对新灯无效", vm.effectRunning.value.not())
        collector.cancel()
    }

    @Test fun `S1_电源部分成功_状态更新且就近披露`() {
        val vm = vm()
        vm.connect(ScannedLamp("A", "AA", -50))
        vm.connect(ScannedLamp("B", "BB", -51))
        lamp.failMac = "AA"
        vm.setPower(false)
        Thread.sleep(150)
        // B 生效（关灯）→ 状态跟随时序语义更新为 false；失败台数就近披露
        assertEquals(false, vm.powerOn.value)
        assertTrue("失败台数应就近披露", vm.powerIssue.value?.contains("1") == true)
        lamp.failMac = null
    }

    @Test fun `S1_定时部分成功保留草稿可重试`() {
        val vm = vm()
        vm.connect(ScannedLamp("A", "AA", -50))
        vm.connect(ScannedLamp("B", "BB", -51)) // 双灯：A 失败 B 成功=真"部分"
        lamp.failMac = "AA"
        vm.updateTimerSlot(true, 8, 30, listOf(true, false, false, false, false, false, false))
        vm.saveTimerSlot(true)
        Thread.sleep(150)
        val slot = vm.timer.value.open
        assertEquals(AppViewModel.TimerPhase.Sent, slot.phase)
        assertEquals("部分成功不得置 clean", true, slot.dirty)
        assertTrue(slot.partial)
        lamp.failMac = null
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
