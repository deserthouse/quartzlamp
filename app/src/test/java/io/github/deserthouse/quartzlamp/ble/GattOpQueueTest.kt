package io.github.deserthouse.quartzlamp.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每设备 GATT 串行队列（R19-04 关闭条件）——复审探针反例的正式回归：
 * 慢 ACK 下普通写不得穿插查询事务；一次至多一个在途；律动 conflate；超时跳过不卡队列。
 *
 * 调度策略：队列循环与挂起调用方均跑在 UnconfinedTestDispatcher——post/ack 后同步推进到
 * 下一挂起点，顺序全确定；超时用虚拟时钟 advanceTimeBy 驱动。
 */
class GattOpQueueTest {

    private class FakeTransport : GattTransport {
        val ops = mutableListOf<String>()
        val pendingWriteAcks = ArrayDeque<(Boolean, String?) -> Unit>()
        var pendingRead: ((ByteArray?, String?) -> Unit)? = null

        override fun enableNotify(done: (Boolean, String?) -> Unit) {
            ops += "N"; done(true, null)
        }
        override fun write(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            ops += "W:" + frame.joinToString("") { "%02X".format(it) }
            pendingWriteAcks.addLast(done)
        }
        override fun read(done: (ByteArray?, String?) -> Unit) {
            ops += "R"; pendingRead = done
        }
        fun ack(ok: Boolean = true) = pendingWriteAcks.removeFirst()(ok, null)
        fun ackRead(data: ByteArray?) {
            val p = pendingRead ?: error("no pending read")
            pendingRead = null; p(data, null)
        }
    }

    private fun f(vararg b: Int) = b.map { it.toByte() }.toByteArray()
    private fun hex(a: ByteArray) = a.joinToString("") { "%02X".format(it) }

    @Test
    fun `普通写串行不重叠`() = runTest {
        val t = FakeTransport()
        val q = GattOpQueue("t", t, 60_000)
        q.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        q.post(f(0xBC, 1))
        // 第一帧在途等 ACK，第二帧不得发出
        assertEquals(listOf("W:BC01"), t.ops)
        q.post(f(0xBC, 2))
        assertEquals(listOf("W:BC01"), t.ops) // 仍被第一帧挡住
        t.ack()
        assertEquals(listOf("W:BC01", "W:BC02"), t.ops)
        t.ack()
    }

    @Test
    fun `查询事务不被普通写穿插-探针反例回归`() = runTest {
        val t = FakeTransport()
        val q = GattOpQueue("t", t, 60_000)
        q.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val power = f(0xBC, 0x01, 0x01, 0x01, 0x55)
        val query = f(0x7E, 0x08, 0x82, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0xEF)
        val extra = f(0xBC, 0x99)

        q.post(power)
        assertEquals(listOf("W:" + hex(power)), t.ops)

        val result = CompletableDeferred<ByteArray?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            result.complete(q.query(query))
        }
        q.post(extra) // 队列序：query → extra（都压在 power 后面）
        // power 在途：查询写与 extra 均不得发出——串行保证
        assertEquals(listOf("W:" + hex(power)), t.ops)

        t.ack() // power 完成 → 查询写进入
        assertEquals(listOf("W:" + hex(power), "W:" + hex(query)), t.ops)
        // extra 压在查询事务后：查询写在途时不得穿插
        t.ack() // 查询写完成 → 读发起
        assertEquals(listOf("W:" + hex(power), "W:" + hex(query), "R"), t.ops)
        assertTrue(!result.isCompleted)

        val readData = f(0x7E, 0x08, 0x82, 0x07, 0x00)
        t.ackRead(readData) // 读完成 → extra 才执行
        assertEquals(
            listOf("W:" + hex(power), "W:" + hex(query), "R", "W:" + hex(extra)), t.ops
        )
        assertTrue(result.isCompleted)
        assertTrue(result.getCompleted()!!.contentEquals(readData))
        t.ack()
    }

    @Test
    fun `律动帧conflate只保留最新`() = runTest {
        val t = FakeTransport()
        val q = GattOpQueue("t", t, 60_000)
        q.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val r1 = f(0xBC, 0x09, 0x01)
        val r2 = f(0xBC, 0x09, 0x02)
        val r3 = f(0xBC, 0x09, 0x03)
        q.postReactive(r1)
        assertEquals(listOf("W:" + hex(r1)), t.ops) // r1 进入在途
        q.postReactive(r2) // r1 在途（已开始执行）→ 排新容器
        q.postReactive(r3) // r2 未开始 → 被替换为 r3
        t.ack() // r1 完成 → r3 执行
        assertEquals(listOf("W:" + hex(r1), "W:" + hex(r3)), t.ops)
        t.ack()
        assertEquals(2, t.ops.size) // 无积压
    }

    @Test
    fun `操作超时跳过不卡队列`() = runTest {
        val t = FakeTransport()
        val q = GattOpQueue("t", t, 100)
        q.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val a = f(0xBC, 1)
        val b = f(0xBC, 2)
        q.post(a) // 永不 ACK
        assertEquals(listOf("W:" + hex(a)), t.ops)
        q.post(b)
        assertEquals(1, t.ops.size) // a 在途挡住 b
        advanceTimeBy(101) // a 超时
        runCurrent()
        assertEquals(listOf("W:" + hex(a), "W:" + hex(b)), t.ops) // b 接上
        // 队列仍活：a 的迟到回调已被超时废弃，先弹掉再确认 b 的回调
        t.ack() // a 的陈旧回调（恢复无效果，安全）
        t.ack() // b 完成 → c 执行
        q.post(f(0xBC, 3))
        assertEquals(3, t.ops.size)
        t.ack()
    }

    @Test
    fun `查询写失败返回null不读`() = runTest {
        val t = FakeTransport()
        val q = GattOpQueue("t", t, 60_000)
        q.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        val result = CompletableDeferred<ByteArray?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            result.complete(q.query(f(0x7E, 1)))
        }
        assertEquals(1, t.ops.size)
        t.ack(ok = false)
        assertTrue(result.isCompleted)
        assertEquals(null, result.getCompleted())
        assertEquals(1, t.ops.size) // 未发起读
        Unit
    }

    @Test
    fun `close后等待者立即失败-R20-06`() = runTest {
        val t = FakeTransport()
        val q = GattOpQueue("t", t, 60_000)
        q.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        q.post(f(0xBC, 1))
        assertEquals(listOf("W:BC01"), t.ops) // 在途等 ACK
        val w = CompletableDeferred<Result<Boolean>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            w.complete(runCatching { q.write(f(0xBC, 2)) })
        }
        advanceUntilIdle()
        q.close() // 排队等待者统一失败（取消异常）
        assertTrue(w.isCompleted)
        assertTrue(w.getCompleted().isFailure)
    }

    @Test
    fun `close后post与reactive不再执行-R20-06`() = runTest {
        val t = FakeTransport()
        val q = GattOpQueue("t", t, 60_000)
        q.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        q.close()
        val before = t.ops.size
        q.post(f(0xBC, 1))
        q.postReactive(f(0xBC, 9))
        assertEquals(before, t.ops.size)
        Unit
    }
}
