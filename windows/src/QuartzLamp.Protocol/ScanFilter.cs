namespace QuartzLamp.Protocol;

/// <summary>
/// 拾音灯广播识别 —— 对齐 Android protocol/ScanFilter.kt。
/// 识别字节 22 20 位于首 AD 段长度偏移处：record[len+3]==0x22 && record[len+4]==0x20
/// （厂商 MyApplication.&lt;init&gt; 实证）。长度字节按无符号解（&gt;127 的 AD 段长现实不存在，
/// 取防御口径：越界折为 false 不抛，与厂商签名行为一致）。
/// </summary>
public static class ScanFilter
{
    public const byte Byte1 = 0x22;
    public const byte Byte2 = 0x20;

    public static bool IsLampBroadcast(ReadOnlySpan<byte> scanRecord)
    {
        if (scanRecord.Length < 8) return false;
        var len = scanRecord[0];
        var p1 = len + 3;
        var p2 = len + 4;
        if (p2 >= scanRecord.Length) return false;
        return scanRecord[p1] == Byte1 && scanRecord[p2] == Byte2;
    }
}
