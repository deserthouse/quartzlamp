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

    @Test fun `VM建立时已连接的会话_补偿发送初始化三帧`() {
        // 就绪事件发生在没有 ViewModel 的期间（如后台自动重连成功）：SharedFlow 无重放，
        // 仅靠订阅会永久漏发——VM 建立时须对已连接会话补发一次
        val lamp = TestLampConnection()
        lamp.connect(ScannedLamp("A", "AA:BB", -50))
        assertEquals("前置：连接时无订阅者，尚无初始化帧", 0, lamp.sentFrames.count { it.startsWith("BC0F") })
        val vm = AppViewModel(lamp)
        assertTrue("补发灯载麦源 BC0F0100", lamp.sentFrames.contains("BC0F010055"))
        assertTrue("补发拾音类型 BC1101", lamp.sentFrames.contains("BC11010155"))
        assertTrue("补发拾音灵敏度 BC1201", lamp.sentFrames.any { it.startsWith("BC1201") })
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
