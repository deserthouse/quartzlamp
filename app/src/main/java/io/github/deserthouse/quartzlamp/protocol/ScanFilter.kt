package io.github.deserthouse.quartzlamp.protocol

/**
 * 厂商扫描广播过滤（纯函数，可单测）——识别字节 22 20，首 AD 段长度偏移法：
 * `scanRecord[scanRecord[0]+3] == 0x22 && scanRecord[scanRecord[0]+4] == 0x20`
 * （厂商 MyApplication.<init> 实证）。长度字节按无符号解（>127 的 AD 段长现实不存在，
 * 此处取防御口径，对真实广播与厂商签名行为一致）。
 */
object ScanFilter {
    const val BYTE1 = 0x22
    const val BYTE2 = 0x20

    fun isLampBroadcast(scanRecord: ByteArray?): Boolean {
        if (scanRecord == null || scanRecord.size < 8) return false
        val len = scanRecord[0].toInt() and 0xFF
        val p1 = len + 3
        val p2 = len + 4
        if (p1 < 0 || p2 >= scanRecord.size) return false
        return scanRecord[p1] == BYTE1.toByte() && scanRecord[p2] == BYTE2.toByte()
    }
}
