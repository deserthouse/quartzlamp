package io.github.deserthouse.quartzlamp.ble

import io.github.deserthouse.quartzlamp.ble.ScannedLamp
import io.github.deserthouse.quartzlamp.ble.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GAP-1（R44-02 验收矩阵·Fake 层契约）：重连预算按序列逐次推进——
 * 两败一成→CONNECTED、全败→DISCONNECTED、退避中主动断开→不复活、连接中取消→不进重连。
 * （FastBle 真实路径的时序用例仍属受控 Android/真机探针，本类只锁 Fake 契约。）
 */
class ReconnectBudgetTest {

    private fun fake(): FakeLampConnection = FakeLampConnection().apply {
        connectDelayMs = 50
        reconnectDelayMs = 50
        scanDelayMs = 50
        scanTimeoutMs = 100
    }

    private fun waitConnected(f: FakeLampConnection, mac: String) {
        runBlocking {
            val deadline = System.currentTimeMillis() + 3_000
            while (f.sessions.value[mac] != SessionState.CONNECTED && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    private fun waitState(f: FakeLampConnection, mac: String, s: SessionState) {
        runBlocking {
            val deadline = System.currentTimeMillis() + 3_000
            while (f.sessions.value[mac] != s && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    private fun firstLamp(f: FakeLampConnection): ScannedLamp {
        // Fake 白名单校验：先扫描出内置假灯再连接
        f.startScan()
        runBlocking {
            val deadline = System.currentTimeMillis() + 3_000
            while (f.scanned.value.isEmpty() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }
        }
        f.stopScan()
        return f.scanned.value.first()
    }

    @Test fun `两败一成_重连成功落CONNECTED`() = runBlocking {
        val f = fake()
        val lampA = firstLamp(f)
        f.connect(lampA)
        waitConnected(f, lampA.mac)
        f.reconnectFailSeq.addAll(listOf(false, false, true))
        f.unexpectedDisconnect(lampA.mac)
        waitState(f, lampA.mac, SessionState.CONNECTED)
        assertEquals("第三次成功应落 CONNECTED", SessionState.CONNECTED, f.sessions.value[lampA.mac])
    }

    @Test fun `全败_预算耗尽落DISCONNECTED`() = runBlocking {
        val f = fake()
        val lampA = firstLamp(f)
        f.connect(lampA)
        waitConnected(f, lampA.mac)
        f.reconnectFailSeq.addAll(listOf(false, false, false, false))
        f.unexpectedDisconnect(lampA.mac)
        waitState(f, lampA.mac, SessionState.DISCONNECTED)
        assertEquals("预算耗尽应落 DISCONNECTED", SessionState.DISCONNECTED, f.sessions.value[lampA.mac])
    }

    @Test fun `退避中主动断开_不复活连接`() = runBlocking {
        val f = fake()
        val lampA = firstLamp(f)
        f.connect(lampA)
        waitConnected(f, lampA.mac)
        f.reconnectFailSeq.addAll(listOf(false, false, true)) // 序列本可成功
        f.unexpectedDisconnect(lampA.mac)
        Thread.sleep(80) // 进入退避窗口
        f.disconnect(lampA.mac) // 退避中主动断开
        Thread.sleep(600) // 越过序列本可成功的时间点
        assertEquals("主动断开后不得复活", SessionState.DISCONNECTED, f.sessions.value[lampA.mac])
        Unit
    }

    @Test fun `意外断连进入RECONNECTING`() = runBlocking {
        val f = fake()
        val lampA = firstLamp(f)
        f.connect(lampA)
        waitConnected(f, lampA.mac)
        f.reconnectSucceeds = false
        f.reconnectDelayMs = 3_000 // 拉长退避，抓中间态
        f.unexpectedDisconnect(lampA.mac)
        Thread.sleep(100)
        assertEquals("意外断连应先落 RECONNECTING", SessionState.RECONNECTING, f.sessions.value[lampA.mac])
        f.disconnect(lampA.mac) // 清理，避免重连挂尾
    }
}
