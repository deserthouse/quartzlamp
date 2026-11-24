package io.github.deserthouse.quartzlamp.ble

import com.clj.fastble.callback.BleGattCallback
import com.clj.fastble.callback.BleScanCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.OtherException
import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **真实会话类**的状态机回归（release 包内的 FastBleLampConnection 本体，经 [BleGateway] 缝驱动 gatt 回调）。
 *
 * 此前"重连矩阵"只锁 debug 假灯（FakeLampConnection 是另一套结构），产品类零直接测试——
 * 意外断连后重连一次都不发起的缺陷因此穿过连续多轮审查。本类把状态机纳入 JVM 回归：
 * 意外断连重连发起 / 预算逐次推进 / 预算耗尽终态 / 退避中主动断开不复活 /
 * 首连失败终态 / 陈旧回调守卫 / 重复连接不并行 / 扫描事实归位。
 *
 * 调度：生产 scope 是 Dispatchers.Main.immediate——由主线程回调调用时协程体同步启动到首个挂起点。
 * 这里以 UnconfinedTestDispatcher 建模同一语义（isDispatchNeeded=false），退避/终态超时走虚拟时钟。
 * GATT 读写/notify 在 JVM 上无实现，队列内会落传输失败——不影响会话态断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FastBleLampConnectionSessionTest {

    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:FF"
        const val BACKOFF = FastBleLampConnection.RECONNECT_INTERVAL_MS
        const val BUDGET = FastBleLampConnection.RECONNECT_ATTEMPTS
    }

    /** 脚本化设备：JVM 下 android.bluetooth 桩不可用，覆写身份访问器 */
    private class ScriptDevice : BleDevice(null as android.bluetooth.BluetoothDevice?) {
        override fun getName() = "Lamp"
        override fun getMac(): String = MAC
        override fun getScanRecord(): ByteArray =
            ByteArray(12).also { it[0] = 7; it[10] = 0x22; it[11] = 0x20 } // 首段长 7 → [10][11]=22 20
    }

    /**
     * 脚本化网关：记录每次 connect 的回调，测试据此驱动 gatt 事件。
     * [connectedTable] 建模 FastBle 的"已连接表"——按设备键、先到先得（MultipleBluetoothController.addBleBluetooth）；
     * notify/write/read 都按设备键查这张表，所以"表里是谁"决定了会话的操作落到哪个句柄上。
     */
    private class ScriptGateway : BleGateway {
        var scanCallback: BleScanCallback? = null
            private set
        val connects = ArrayList<Pair<BleDevice, BleGattCallback>>()
        val last: BleGattCallback get() = connects.last().second
        val connectedTable = HashMap<String, android.bluetooth.BluetoothGatt>()

        /** 非 null 时：第 N 次 connect 调用（1 起）在记录后同步抛异常——模拟传输层入口抛出运行时异常 */
        var throwOnConnectFrom: Int? = null

        override fun scan(callback: BleScanCallback) { scanCallback = callback }
        override fun cancelScan() {}
        override fun connect(device: BleDevice, callback: BleGattCallback) {
            connects += device to callback
            if (throwOnConnectFrom != null && connects.size >= throwOnConnectFrom!!) {
                throw IllegalStateException("transport entry blew up")
            }
        }

        /** 模拟"第 [index] 次尝试服务发现成功"：先按先到先得登记，再回调——与 BleBluetooth 的 MSG_DISCOVER_SUCCESS 同序 */
        fun succeed(index: Int, gatt: android.bluetooth.BluetoothGatt) {
            val (device, callback) = connects[index]
            connectedTable.putIfAbsent(device.mac, gatt)
            callback.onConnectSuccess(device, gatt, 0)
        }

        override fun evictConnectedIfOwnedBy(
            device: BleDevice,
            gatt: android.bluetooth.BluetoothGatt,
        ): Boolean {
            if (connectedTable[device.mac] !== gatt) return false
            connectedTable.remove(device.mac)
            return true
        }
    }

    /** JVM 上 BluetoothGatt 的构造器是隐藏 API——这里只需要一个有身份的对象，绕过构造器分配 */
    private fun newGatt(): android.bluetooth.BluetoothGatt {
        val theUnsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        theUnsafe.isAccessible = true
        return (theUnsafe.get(null) as sun.misc.Unsafe)
            .allocateInstance(android.bluetooth.BluetoothGatt::class.java) as android.bluetooth.BluetoothGatt
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.lamp(): Pair<FastBleLampConnection, ScriptGateway> {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val gw = ScriptGateway()
        return FastBleLampConnection(gw) to gw
    }

    /** 扫描出灯（并结束扫描窗口，模拟 8s 超时自然结束），返回已连接的会话 */
    private fun TestScope.discovered(): Triple<FastBleLampConnection, ScriptGateway, ScannedLamp> {
        val (conn, gw) = lamp()
        conn.startScan()
        gw.scanCallback!!.onScanning(ScriptDevice())
        val lamp = conn.scanned.value.single()
        gw.scanCallback!!.onScanFinished(emptyList())
        return Triple(conn, gw, lamp)
    }

    /** 扫描出灯并连接成功（走真实类的扫描过滤与会话建立路径） */
    private fun TestScope.connected(): Triple<FastBleLampConnection, ScriptGateway, ScannedLamp> {
        val (conn, gw, lamp) = discovered()
        conn.connect(lamp)
        assertEquals(SessionState.CONNECTING, conn.sessions.value[MAC])
        gw.last.onConnectSuccess(ScriptDevice(), null, 0)
        assertEquals(SessionState.CONNECTED, conn.sessions.value[MAC])
        return Triple(conn, gw, lamp)
    }

    /** 推进一个退避周期，使挂起中的重连循环走到发起下一条 connect */
    private fun TestScope.nextAttempt() {
        advanceTimeBy(BACKOFF)
        runCurrent()
    }

    // ━━━ 意外断连 → 自动重连 ━━━

    @Test fun `意外断连_退避到期后发起重连尝试`() = runTest {
        val (conn, gw, _) = connected()
        gw.last.onDisConnected(false, ScriptDevice(), null, 0) // isActive=false：非用户发起
        assertEquals(SessionState.RECONNECTING, conn.sessions.value[MAC])

        advanceTimeBy(BACKOFF - 1); runCurrent()
        assertEquals("退避期内不得发起", 1, gw.connects.size)

        nextAttempt()
        assertEquals("退避到期应发起重连尝试", 2, gw.connects.size)
        assertEquals(SessionState.CONNECTING, conn.sessions.value[MAC])
        conn.disconnect(MAC)
    }

    @Test fun `重连_两败一成_落CONNECTED`() = runTest {
        val (conn, gw, _) = connected()
        gw.last.onDisConnected(false, ScriptDevice(), null, 0)
        repeat(2) {
            nextAttempt()
            gw.last.onConnectFail(ScriptDevice(), OtherException("link lost"))
            assertNotEquals("单次失败不得终结预算", SessionState.DISCONNECTED, conn.sessions.value[MAC])
        }
        nextAttempt()
        assertEquals(1 + BUDGET, gw.connects.size)
        gw.last.onConnectSuccess(ScriptDevice(), null, 0)
        assertEquals("预算内成功应落 CONNECTED", SessionState.CONNECTED, conn.sessions.value[MAC])
        conn.disconnect(MAC)
    }

    @Test fun `重连_预算耗尽_落DISCONNECTED_此后首连失败仍回DISCONNECTED`() = runTest {
        val (conn, gw, lamp) = connected()
        gw.last.onDisConnected(false, ScriptDevice(), null, 0)
        repeat(BUDGET) {
            nextAttempt()
            gw.last.onConnectFail(ScriptDevice(), OtherException("link lost"))
        }
        runCurrent()
        assertEquals(1 + BUDGET, gw.connects.size)
        assertEquals("预算耗尽应落 DISCONNECTED", SessionState.DISCONNECTED, conn.sessions.value[MAC])

        // 重连周期之后的用户连接属于"首次连接"：失败必须回 DISCONNECTED，不得卡在 CONNECTING
        conn.connect(lamp)
        assertEquals(2 + BUDGET, gw.connects.size)
        assertEquals(SessionState.CONNECTING, conn.sessions.value[MAC])
        gw.last.onConnectFail(ScriptDevice(), OtherException("still away"))
        assertEquals("首连失败应回 DISCONNECTED", SessionState.DISCONNECTED, conn.sessions.value[MAC])
    }

    @Test fun `退避中主动断开_不复活`() = runTest {
        val (conn, gw, _) = connected()
        gw.last.onDisConnected(false, ScriptDevice(), null, 0)
        advanceTimeBy(BACKOFF / 2); runCurrent()
        conn.disconnect(MAC)
        assertEquals(SessionState.DISCONNECTED, conn.sessions.value[MAC])

        advanceTimeBy(BACKOFF * (BUDGET + 2)); runCurrent()
        assertEquals("主动断开后不得再发起连接", 1, gw.connects.size)
        assertEquals(SessionState.DISCONNECTED, conn.sessions.value[MAC])
    }

    @Test fun `终态迟迟未到_兜底超时前不叠发_到期后推进下一轮`() = runTest {
        val (conn, gw, _) = connected()
        gw.last.onDisConnected(false, ScriptDevice(), null, 0)
        nextAttempt()
        assertEquals(2, gw.connects.size)
        // 第 2 次尝试不回任何终态（模拟 FastBle 内部重试序列尚未走完）
        advanceTimeBy(FastBleLampConnection.CONNECT_ATTEMPT_TIMEOUT_MS - 1); runCurrent()
        assertEquals("兜底到期前不得叠发新连接", 2, gw.connects.size)
        advanceTimeBy(1); runCurrent()
        assertEquals("兜底到期当刻仍不叠发（下一轮照常先退避）", 2, gw.connects.size)
        nextAttempt()
        assertEquals("兜底到期后退避满一轮应推进下一轮", 3, gw.connects.size)
        assertNotEquals(SessionState.DISCONNECTED, conn.sessions.value[MAC])
        conn.disconnect(MAC)
    }

    @Test fun `兜底超时覆盖FastBle内部重试序列上限`() {
        // FastBle init 参数（厂商同款）：重试 3 次、间隔 4s、单次连接超时 99s
        val fastBleSequenceUpperBound = (3 + 1) * 99_000L + 3 * 4_000L
        assertTrue(
            "兜底 ${FastBleLampConnection.CONNECT_ATTEMPT_TIMEOUT_MS}ms 须 ≥ FastBle 单轮序列上限 $fastBleSequenceUpperBound ms",
            FastBleLampConnection.CONNECT_ATTEMPT_TIMEOUT_MS >= fastBleSequenceUpperBound
        )
    }

    // ━━━ 首次连接 ━━━

    @Test fun `首连失败_落DISCONNECTED`() = runTest {
        val (conn, gw, lamp) = discovered()
        conn.connect(lamp)
        gw.last.onConnectFail(ScriptDevice(), OtherException("GATT ERROR"))
        assertEquals(SessionState.DISCONNECTED, conn.sessions.value[MAC])
        assertEquals(ConnState.DISCONNECTED, conn.state.value)
    }

    @Test fun `重复连接调用_不并行发起`() = runTest {
        val (conn, gw, lamp) = discovered()
        conn.connect(lamp)
        conn.connect(lamp) // FastBle 无取消连接 API：并行 connectGatt 会互相干扰
        assertEquals("在途会话上的重复连接应被忽略", 1, gw.connects.size)
        conn.disconnect(MAC)
        assertEquals(ConnState.DISCONNECTED, conn.state.value)
    }

    // ━━━ 陈旧回调守卫 ━━━

    @Test fun `陈旧连接成功_不得建立会话`() = runTest {
        val (conn, gw, lamp) = discovered()
        conn.connect(lamp)
        val stale = gw.last
        conn.disconnect(MAC) // 使在途尝试过期
        conn.connect(lamp) // 新尝试
        assertEquals(2, gw.connects.size)

        stale.onConnectSuccess(ScriptDevice(), null, 0) // 迟到成功
        assertEquals("陈旧成功不得改写会话态", SessionState.CONNECTING, conn.sessions.value[MAC])
        assertFalse("陈旧成功不得建立可写队列", conn.sendTo(MAC, LampFrames.power(true)))

        gw.last.onConnectSuccess(ScriptDevice(), null, 0)
        assertEquals(SessionState.CONNECTED, conn.sessions.value[MAC])
        assertEquals(ConnState.CONNECTED, conn.state.value)
        conn.disconnect(MAC)
    }

    @Test fun `陈旧断开回调_零影响`() = runTest {
        val (conn, gw, lamp) = discovered()
        conn.connect(lamp)
        val stale = gw.last
        conn.disconnect(MAC)
        conn.connect(lamp)
        stale.onDisConnected(false, ScriptDevice(), null, 0)
        stale.onConnectFail(ScriptDevice(), OtherException("late"))
        assertEquals("陈旧失败/断开不得改写新尝试", SessionState.CONNECTING, conn.sessions.value[MAC])
        conn.disconnect(MAC)
    }

    // ━━━ 取消后立即再连：传输层"已连接表"的归属 ━━━
    //
    // "连接中点取消"取消不了 FastBle 的在途连接（2.3.4 无此 API），后台仍会按其内部序列重试；
    // 取消后立刻再点"连接"= 两个 connectGatt 并行。FastBle 的已连接表先到先得：
    // 旧尝试若抢先连上就占住表位，新尝试连上后不被登记，此后操作全路由到我方即将关闭的旧句柄。

    @Test fun `取消后立即再连_旧尝试抢先连上_不得占住已连接表`() = runTest {
        val (conn, gw, lamp) = discovered()
        val gattOld = newGatt()
        val gattNew = newGatt()
        conn.connect(lamp) // 尝试 1
        conn.disconnect(MAC) // 用户取消
        conn.connect(lamp) // 立即再连：尝试 2，与旧尝试并行
        assertEquals(2, gw.connects.size)

        gw.succeed(0, gattOld) // 旧尝试抢先把设备连上：FastBle 先到先得登记它
        assertEquals("陈旧成功不得建立会话", SessionState.CONNECTING, conn.sessions.value[MAC])

        gw.succeed(1, gattNew) // 新尝试连上
        assertEquals(SessionState.CONNECTED, conn.sessions.value[MAC])
        assertSame(
            "已连接表必须指向新会话的句柄——否则此后 notify/write/read 全部落到已关闭的旧句柄",
            gattNew, gw.connectedTable[MAC]
        )
        conn.disconnect(MAC)
    }

    @Test fun `新尝试先连上_旧尝试后到成功_不误清已连接表`() = runTest {
        val (conn, gw, lamp) = discovered()
        val gattOld = newGatt()
        val gattNew = newGatt()
        conn.connect(lamp)
        conn.disconnect(MAC)
        conn.connect(lamp)

        gw.succeed(1, gattNew)
        assertEquals(SessionState.CONNECTED, conn.sessions.value[MAC])
        gw.succeed(0, gattOld) // 旧尝试迟到成功：表里已是新句柄，不得被清
        assertEquals(SessionState.CONNECTED, conn.sessions.value[MAC])
        assertSame(gattNew, gw.connectedTable[MAC])
        conn.disconnect(MAC)
    }

    @Test fun `重连尝试的传输层入口抛异常_按本次失败计_预算耗尽落DISCONNECTED`() = runTest {
        val (conn, gw, _) = connected()
        gw.throwOnConnectFrom = 2 // 首次重连尝试起，传输层入口同步抛异常
        gw.last.onDisConnected(false, ScriptDevice(), null, 0)
        repeat(BUDGET) { nextAttempt() }
        runCurrent()
        assertEquals(1 + BUDGET, gw.connects.size)
        assertEquals(
            "异常尝试应按失败计、预算照常推进，耗尽后落 DISCONNECTED（不得带着异常终止而让会话卡在 CONNECTING）",
            SessionState.DISCONNECTED, conn.sessions.value[MAC]
        )
    }

    // ━━━ 扫描独立事实 ━━━

    @Test fun `扫描超时与停止_扫描事实归位`() = runTest {
        val (conn, gw) = lamp()
        conn.startScan()
        assertTrue(conn.scanning.value)
        assertEquals(ConnState.SCANNING, conn.state.value)
        gw.scanCallback!!.onScanning(ScriptDevice())
        assertEquals("广播识别字节 22 20 命中才入列", 1, conn.scanned.value.size)
        gw.scanCallback!!.onScanFinished(emptyList())
        assertFalse("超时自然结束应清扫描事实", conn.scanning.value)
        assertEquals(ConnState.DISCONNECTED, conn.state.value)

        conn.startScan()
        assertTrue(conn.scanning.value)
        conn.stopScan()
        assertFalse("手动停止应清扫描事实", conn.scanning.value)
    }

    @Test fun `扫描启动失败_扫描事实归位`() = runTest {
        val (conn, gw) = lamp()
        conn.startScan()
        gw.scanCallback!!.onScanStarted(false) // 蓝牙关闭/栈冲突
        assertFalse(conn.scanning.value)
        assertEquals(ConnState.DISCONNECTED, conn.state.value)
    }

    @Test fun `连接建立后扫描起停_不误杀连接`() = runTest {
        val (conn, gw, _) = connected()
        conn.startScan()
        assertTrue(conn.scanning.value)
        // 扫描窗口内聚合态为 SCANNING 是既定设计（与 FakeLampContractTest 同口径）；行级会话态不受影响
        assertEquals(ConnState.SCANNING, conn.state.value)
        assertEquals(SessionState.CONNECTED, conn.sessions.value[MAC])
        conn.stopScan()
        assertEquals(SessionState.CONNECTED, conn.sessions.value[MAC])
        assertEquals(ConnState.CONNECTED, conn.state.value)
        conn.disconnect(MAC)
    }
}
