package io.github.deserthouse.quartzlamp.ui

import io.github.deserthouse.quartzlamp.protocol.LampFrames
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
 * 定时槽位 VM 逻辑（审查 F05/F06 关闭条件）：
 * 交替读两槽不串；未设置清空本槽；读一槽不丢另一槽草稿；读取失败不清草稿只置 error。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTimerTest {

    private val lamp = TestLampConnection()
    private val vm = AppViewModel(lamp)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        lamp.connect(io.github.deserthouse.quartzlamp.ble.ScannedLamp("t", "AA", -50))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun unsetFrame(open: Boolean) = byteArrayOf(
        0x7E, 0x08, 0x82.toByte(), -1, -1, -1, if (open) 0 else 1, 0, 0xEF.toByte()
    )

    private fun setFrame(h: Int, m: Int, week: Int, open: Boolean) = byteArrayOf(
        0x7E, 0x08, 0x82.toByte(), h.toByte(), m.toByte(), 0,
        (if (open) 0 else 1).toByte(), week.toByte(), 0xEF.toByte()
    )

    @Test fun `读开灯槽不丢关灯槽草稿`() {
        // 关灯槽先编辑草稿 22:30 周日
        vm.updateTimerSlot(false, 22, 30, listOf(true, false, false, false, false, false, false))
        // 读开灯槽（07:30 一三五）
        lamp.timerRead = { setFrame(7, 30, 0b0010101, open = true) }
        vm.queryTimerSlot(true)
        val t = vm.timer.value
        assertEquals(7, t.open.hour)
        assertTrue(t.open.loaded)
        // 关灯槽草稿原样
        assertEquals(22, t.close.hour)
        assertEquals(30, t.close.minute)
        assertTrue(t.close.weeks[0])
        assertFalse(t.close.loaded)
    }

    @Test fun `未设置读回清空本槽`() {
        lamp.timerRead = { setFrame(7, 30, 0x15, open = true) }
        vm.queryTimerSlot(true)
        assertTrue(vm.timer.value.open.loaded)
        // 灯端已被清（未设置）
        lamp.timerRead = { unsetFrame(open = true) }
        vm.queryTimerSlot(true)
        assertFalse(vm.timer.value.open.loaded)
        assertEquals("未设置语义下本槽回默认草稿", 7, vm.timer.value.open.hour)
    }

    @Test fun `读取失败置error且不清草稿`() {
        vm.updateTimerSlot(true, 9, 45, List(7) { false })
        lamp.timerRead = { null }
        vm.queryTimerSlot(true)
        assertTrue(vm.timer.value.open.error)
        assertEquals(9, vm.timer.value.open.hour)
        assertEquals(45, vm.timer.value.open.minute)
    }

    @Test fun `保存即下发无启用位`() {
        vm.updateTimerSlot(true, 7, 30, listOf(true, false, true, false, true, false, false))
        vm.saveTimerSlot(true)
        // 7E 08 82 07 1E 00 00 15 EF —— byte[6]=0（开灯），无任何"启用"参数参与
        assertTrue(lamp.sentFrames.contains("7E0882" + "071E" + "00" + "00" + "15EF"))
        assertEquals(
            "7E0882071E000015EF",
            LampFrames.hex(LampFrames.timerSet(7, 30, open = true, weekBits = 0b0010101))
        )
    }

    @Test fun `交替读两槽互不串扰`() {
        lamp.timerRead = { open -> if (open) setFrame(7, 30, 0x15, true) else setFrame(23, 0, 0x40, false) }
        vm.queryTimerSlot(true)
        vm.queryTimerSlot(false)
        vm.queryTimerSlot(true)
        val t = vm.timer.value
        assertEquals(7, t.open.hour)
        assertTrue(t.open.loaded)
        assertEquals(23, t.close.hour)
        assertTrue(t.close.loaded)
        assertTrue(t.close.weeks[6]) // 0x40 = 周日
    }

    @Test fun `编辑标草稿_保存置已发送-R19-09`() {
        vm.updateTimerSlot(true, 9, 45, List(7) { false })
        assertTrue(vm.timer.value.open.dirty)
        vm.saveTimerSlot(true)
        val s = vm.timer.value.open
        assertTrue(s.sent)
        assertFalse(s.dirty)
    }

    @Test fun `未设置关灯槽恢复23点默认-R19-09`() {
        lamp.timerRead = { unsetFrame(open = false) }
        vm.queryTimerSlot(false)
        val close = vm.timer.value.close
        assertEquals(23, close.hour)
        assertFalse(close.loaded)
    }

    @Test fun `读回记录读取目标灯-R19-09`() {
        lamp.timerRead = { setFrame(7, 30, 0x15, open = true) }
        vm.queryTimerSlot(true)
        assertEquals("AA", vm.timer.value.open.readFrom) // scanned 空时退 MAC
    }
}
