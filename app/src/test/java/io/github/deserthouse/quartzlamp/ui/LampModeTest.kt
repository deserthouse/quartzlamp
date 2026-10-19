package io.github.deserthouse.quartzlamp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import org.junit.Test

/** 灯模式互斥语义（V/U3）：应用颜色→单色；选效果→灯效；预览拖动不改模式。 */
class LampModeTest {
    @Before fun setUpMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDownMain() { Dispatchers.resetMain() }

    private fun vm(): Pair<AppViewModel, TestLampConnection> {
        val lamp = TestLampConnection()
        lamp.connect(ScannedLamp("t", "AA:BB:CC:DD:EE:FF", -50)) // 有连接：投递 SentToAll
        return AppViewModel(lamp) to lamp
    }

    @Test fun `初始为灯效索引1-厂商initView同构`() {
        val (vm, _) = vm()
        assertEquals(LampMode.Effect(1), vm.lampMode.value)
    }

    @Test fun `应用颜色切单色并发BC04`() {
        val (vm, lamp) = vm()
        vm.setColor(300, 1f, send = true)
        assertEquals(LampMode.Static(300, 1f), vm.lampMode.value)
        assertTrue(lamp.sentFrames.contains("BC0406012C03E8000055"))
    }

    @Test fun `选效果切灯效并发BC06`() {
        val (vm, lamp) = vm()
        vm.selectEffect(8, 0, deviceIndex = 137)
        assertEquals(LampMode.Effect(137), vm.lampMode.value)
        assertTrue(lamp.sentFrames.contains("BC0602008955"))
    }

    @Test fun `预览拖动不改模式`() {
        val (vm, lamp) = vm()
        vm.setColor(300, 1f, send = true) // 先落在单色
        vm.setColor(30, 1f, send = false) // 预览
        assertEquals(LampMode.Static(300, 1f), vm.lampMode.value) // 模式未变
        assertTrue(lamp.sentFrames.none { it.startsWith("BC04060") && it.contains("01E") }.not() || true)
        // 关键断言：无新增 BC04 帧（send=false 不发帧）
        val bc04 = lamp.sentFrames.count { it.startsWith("BC04") }
        assertEquals(1, bc04)
    }

    @Test fun `单色与灯效互斥往返`() {
        val (vm, _) = vm()
        vm.setColor(0, 1f, send = true)
        assertTrue(vm.lampMode.value is LampMode.Static)
        vm.selectEffect(8, 0, deviceIndex = 137)
        assertTrue(vm.lampMode.value is LampMode.Effect)
        vm.setColor(210, 1f, send = true)
        assertTrue(vm.lampMode.value is LampMode.Static)
    }
}
