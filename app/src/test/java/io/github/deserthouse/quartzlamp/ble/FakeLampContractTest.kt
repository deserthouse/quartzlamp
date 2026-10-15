package io.github.deserthouse.quartzlamp.ble

import io.github.deserthouse.quartzlamp.protocol.LampFrames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假灯行为契约（审查 F11）：与真实实现同构的可验证面——
 * 多灯会话独立、扫描可取消、定时写读闭环、失败注入。debug 变体专属测试。
 */
class FakeLampContractTest {

    private fun awaitConnected(fake: FakeLampConnection, mac: String) {
        val deadline = System.currentTimeMillis() + 3000
        while (fake.sessions.value[mac] != SessionState.CONNECTED) {
            if (System.currentTimeMillis() > deadline) error("connect timeout: ${fake.sessions.value}")
            Thread.sleep(10)
        }
    }

    @Test fun `两台灯可同时连接且断一台不影响另一台`() = runBlocking {
        val fake = FakeLampConnection().apply { connectDelayMs = 10 }
        fake.startScan()
        // 等扫描回填
        val deadline = System.currentTimeMillis() + 3000
        while (fake.scanned.value.isEmpty()) {
            if (System.currentTimeMillis() > deadline) error("scan timeout")
            Thread.sleep(10)
        }
        val (a, b) = fake.fakeLamps
        fake.connect(a)
        fake.connect(b)
        awaitConnected(fake, a.mac)
        awaitConnected(fake, b.mac)
        // 扫描与连接独立维度：扫描窗口内聚合态保持 SCANNING（设计行为），停扫后聚合为 CONNECTED
        assertEquals(SessionState.CONNECTED, fake.sessions.value[a.mac])
        assertEquals(SessionState.CONNECTED, fake.sessions.value[b.mac])
        fake.stopScan()
        assertEquals(ConnState.CONNECTED, fake.state.value)

        fake.disconnect(a.mac)
        assertEquals(SessionState.DISCONNECTED, fake.sessions.value[a.mac])
        assertEquals(SessionState.CONNECTED, fake.sessions.value[b.mac])
        assertEquals(ConnState.CONNECTED, fake.state.value)
    }

    @Test fun `stopScan后不再回填设备`() = runBlocking {
        val fake = FakeLampConnection()
        fake.startScan()
        fake.stopScan()
        // 回填任务已被取消：正常应 2 秒后出设备，此处 2.4 秒后仍必须为空
        Thread.sleep(2400)
        assertTrue(fake.scanned.value.isEmpty())
        Unit
    }

    @Test fun `定时写读闭环-写入后读回所写槽位`() = runBlocking {
        val fake = FakeLampConnection()
        val mac = fake.fakeLamps[0].mac
        fake.connect(fake.fakeLamps[0])
        awaitConnected(fake, mac)
        // 写开灯定时 07:30 一三五
        fake.send(LampFrames.timerSet(7, 30, open = true, weekBits = 0b0010101))
        val read = fake.queryTimer(open = true)!!
        val info = LampFrames.parseTimerRead(read)!!
        assertTrue(info.isSet)
        assertEquals(7, info.hour)
        assertEquals(30, info.minute)
        assertEquals(0b0010101, info.weekBits)
        // 关灯槽未写 → 未设置帧
        val unset = LampFrames.parseTimerRead(fake.queryTimer(open = false)!!)!!
        assertTrue(!unset.isSet)
    }

    @Test fun `失败注入-查询返回null`() = runBlocking {
        val fake = FakeLampConnection().apply {
            connectDelayMs = 10
            failNextQueries = 1
        }
        fake.connect(fake.fakeLamps[0])
        awaitConnected(fake, fake.fakeLamps[0].mac)
        assertNull(fake.queryTimer(open = true))
        // 注入耗尽后恢复
        assertTrue(fake.queryTimer(open = true) != null)
    }

    @Test fun `意外断连进入重连并可恢复`() = runBlocking {
        val fake = FakeLampConnection().apply {
            connectDelayMs = 10
            reconnectDelayMs = 20
        }
        val mac = fake.fakeLamps[0].mac
        fake.connect(fake.fakeLamps[0])
        awaitConnected(fake, mac)
        fake.unexpectedDisconnect(mac)
        assertEquals(SessionState.RECONNECTING, fake.sessions.value[mac])
        awaitConnected(fake, mac) // 重连成功路径
        // 主动断开后不重连
        fake.disconnect(mac)
        assertEquals(SessionState.DISCONNECTED, fake.sessions.value[mac])
        Unit
    }
}
