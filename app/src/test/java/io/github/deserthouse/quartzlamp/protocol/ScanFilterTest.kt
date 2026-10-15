package io.github.deserthouse.quartzlamp.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 厂商广播过滤纯函数——期望值来自协议文档 §1（识别字节 22 20，首 AD 段长度偏移法） */
class ScanFilterTest {

    private fun rec(firstLen: Int, vararg rest: Int): ByteArray =
        byteArrayOf(firstLen.toByte()) + rest.map { it.toByte() }.toByteArray()

    @Test fun `识别字节命中`() {
        // rec[0]=len(7) → rec[10]=0x22, rec[11]=0x20（构造 12 字节记录）
        val r = rec(7, 0xFF, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0x22, 0x20)
        assertTrue(ScanFilter.isLampBroadcast(r))
    }

    @Test fun `识别字节不符拒绝`() {
        val r = rec(7, 0xFF, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0x22, 0x21)
        assertFalse(ScanFilter.isLampBroadcast(r))
    }

    @Test fun `空记录与超短记录拒绝`() {
        assertFalse(ScanFilter.isLampBroadcast(null))
        assertFalse(ScanFilter.isLampBroadcast(ByteArray(0)))
        assertFalse(ScanFilter.isLampBroadcast(byteArrayOf(3, 1, 2)))
    }

    @Test fun `偏移越界拒绝不抛`() {
        // len 声称 0xF0 但记录短 → 索引越界必须折为 false 而非 AIOOBE
        val r = rec(0xF0 and 0xFF, 0x22, 0x20)
        assertFalse(ScanFilter.isLampBroadcast(r))
    }
}
