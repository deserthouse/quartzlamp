using QuartzLamp.Protocol;
using Xunit;

namespace QuartzLamp.Tests;

/// <summary>对齐 Android ScanFilterTest 的期望（独立源）。</summary>
public sealed class ScanFilterTests
{
    private static byte[] Rec(int firstLen, params byte[] rest)
    {
        var all = new byte[rest.Length + 1];
        all[0] = (byte)firstLen;
        rest.CopyTo(all, 1);
        return all;
    }

    [Fact]
    public void 识别字节命中()
    {
        // rec[0]=len(7) → rec[10]=0x22, rec[11]=0x20（12 字节记录）
        var r = Rec(7, 0xFF, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0x22, 0x20);
        Assert.True(ScanFilter.IsLampBroadcast(r));
    }

    [Fact]
    public void 识别字节不符拒绝()
    {
        var r = Rec(7, 0xFF, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0x22, 0x21);
        Assert.False(ScanFilter.IsLampBroadcast(r));
    }

    [Fact]
    public void 空记录与超短记录拒绝()
    {
        Assert.False(ScanFilter.IsLampBroadcast(ReadOnlySpan<byte>.Empty));
        Assert.False(ScanFilter.IsLampBroadcast(new byte[] { 3, 1, 2 }));
    }

    [Fact]
    public void 偏移越界拒绝不抛()
    {
        // len 声称 0xF0 但记录短 → 索引越界必须折为 false 而非抛异常
        var r = Rec(0xF0, 0x22, 0x20);
        Assert.False(ScanFilter.IsLampBroadcast(r));
    }

    [Fact]
    public void 长度127边界_无符号解不越界()
    {
        // len=127 → p2=131；记录恰 132 字节时命中位有效
        var r = new byte[132];
        r[0] = 127;
        r[130] = 0x22;
        r[131] = 0x20;
        Assert.True(ScanFilter.IsLampBroadcast(r));
        // 同布局但 131 字节 → 越界折 false
        var short_ = r[..131];
        Assert.False(ScanFilter.IsLampBroadcast(short_));
    }
}
