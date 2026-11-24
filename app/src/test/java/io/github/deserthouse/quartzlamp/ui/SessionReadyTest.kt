package io.github.deserthouse.quartzlamp.ui

import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 会话就绪初始化（AV04-01/08 关闭门禁）：
 * 构造期已连接不崩；每灯独立触发；扫描起停零副作用。
 */
class SessionReadyTest {

    @Before fun setUpMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDownMain() { Dispatchers.resetMain() }

    @Test fun `构造期已连接同步路径不崩`() {
        // 连接先行（桩 connect 同步发 sessionReady），VM 构造时收集器立即可达 _mic
        val lamp = TestLampConnection()
        lamp.connect(ScannedLamp("A", "AA:BB", -50))
        val vm = AppViewModel(lamp) // 此前版本在此 NPE（AV04-01）
        // sessionReady 已在连接时发出（extraBuffer 容量），订阅后收到并触发三帧
        assertTrue(vm.mic.value.type in 1..4)
        Unit
    }

    @Test fun `VM重建遇上已连接会话_不得向灯补发拾音配置`() {
        // 拾音类型/灵敏度只存在 ViewModel 里，不跨实例：用户选了"动感"后按返回退出（Activity 结束，
        // 进程与 BLE 连接仍在），再进来时新 VM 只有默认值（经典/50）。此时若对已连接的灯"补发"
        // 三帧，等于悄悄把灯重置回默认——这是正常路径上的行为变化，不允许。
        val lamp = TestLampConnection()
        val first = AppViewModel(lamp)
        lamp.connect(ScannedLamp("A", "AA:BB", -50)) // 首次会话：三帧照常下发
        first.setMicType(3) // 用户选"动感"
        assertTrue(lamp.sentFrames.contains("BC11010355"))
        val before = lamp.sentFrames.size

        val second = AppViewModel(lamp) // Activity 重建：新 VM，灯仍然连接
        assertEquals("新 VM 的拾音状态是默认值（不跨实例）", 1, second.mic.value.type)
        val written = lamp.sentFrames.drop(before)
        assertTrue(
            "VM 重建不得向已连接的灯写任何拾音帧，实际写入：$written",
            written.none { it.startsWith("BC0F") || it.startsWith("BC11") || it.startsWith("BC12") }
        )
        Unit
    }

    @Test fun `VM建立时无已连接会话_不补发`() {
        val lamp = TestLampConnection()
        val vm = AppViewModel(lamp)
        assertTrue(lamp.sentFrames.none { it.startsWith("BC0F") })
        Unit
    }

    @Test fun `每灯独立触发第二盏也有三帧`() {
        val lamp = TestLampConnection()
        val vm = AppViewModel(lamp)
        val a = lamp.sentFrames.count { it.startsWith("BC0F") }
        lamp.connect(ScannedLamp("B", "CC:DD", -51))
        Thread.sleep(100) // 收集器处理
        val b = lamp.sentFrames.count { it.startsWith("BC0F") }
        assertTrue("第二盏接入应再触发（$a → $b）", b > a)
    }

    @Test fun `扫描起停零拾音副作用`() {
        val lamp = TestLampConnection()
        val vm = AppViewModel(lamp)
        lamp.connect(ScannedLamp("A", "AA:BB", -50))
        Thread.sleep(100)
        val before = lamp.sentFrames.count { it.startsWith("BC0F") }
        lamp.startScan()
        lamp.stopScan()
        Thread.sleep(100)
        val after = lamp.sentFrames.count { it.startsWith("BC0F") }
        assertEquals("扫描起停不应写拾音配置（AV04-08）", before, after)
    }

    @Test fun `连接与扫描登记knownLamps且断开不清除`() {
        val lamp = TestLampConnection()
        val vm = AppViewModel(lamp)
        vm.connect(ScannedLamp("A", "AA:BB", -50)) // 登记发生在 VM.connect（AV04-05）
        lamp.scanned.value = listOf(ScannedLamp("B", "CC:DD", -60))
        Thread.sleep(100)
        assertEquals("A", vm.knownLamps.value["AA:BB"]?.name)
        assertEquals("B", vm.knownLamps.value["CC:DD"]?.name)
        lamp.disconnect("AA:BB")
        // AV04-05：断开/重扫清空不得抹掉登记——会话行身份依赖它
        assertEquals("断开不清除登记", "A", vm.knownLamps.value["AA:BB"]?.name)
    }
}
