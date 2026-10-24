namespace QuartzLamp.Protocol;

/// <summary>
/// 拾音灯帧构造器 —— 唯一事实源 docs/ble_protocol.md 的 C# 化。
/// 逐字节对齐 Android 侧 app/src/main/java/.../protocol/LampFrames.kt（含厂商编码怪癖）；
/// 改帧必须同步协议文档、Android Kotlin 与本文件，并以双端黄金向量测试把关。
/// </summary>
public static class LampFrames
{
    public const string ServiceUuid = "0000FFF0-0000-1000-8000-00805f9b34fb";
    public const string CharUuid = "0000FFF3-0000-1000-8000-00805f9b34fb";
    public const string CccdUuid = "00002902-0000-1000-8000-00805f9b34fb";

    // ━━━ BC 族（头 0xBC 尾 0x55）━━━

    /// <summary>帧#1 电源开关。</summary>
    public static byte[] Power(bool on) => Bc5(0x01, 0x01, on ? 1 : 0);

    /// <summary>帧#2 静态调色（h∈[0,360) 色相，s∈[0,1] 饱和度）——大端 16 位 /256（厂商怪癖）。</summary>
    public static byte[] ColorStatic(int h, float s)
    {
        if (h is < 0 or > 359) throw new ArgumentOutOfRangeException(nameof(h), h, "hue out of range");
        var sv = Math.Clamp((int)(s * 1000), 0, 1000);
        return
        [
            0xBC, 0x04, 0x06,
            (byte)(h / 256), (byte)(h % 256),
            (byte)(sv / 256), (byte)(sv % 256),
            0, 0, 0x55,
        ];
    }

    /// <summary>帧#3 亮度（v=明度进度值，厂商下限 3）——大端 16 位 /255。</summary>
    public static byte[] Brightness(int v)
    {
        var vv = Math.Max(v, 3);
        return
        [
            0xBC, 0x05, 0x06,
            (byte)(vv / 255), (byte)(vv % 255),
            0, 0, 0, 0, 0x55,
        ];
    }

    /// <summary>帧#4 灯效模式选择（大端 16 位 /255）。</summary>
    public static byte[] ModeSelect(int index) =>
    [
        0xBC, 0x06, 0x02,
        (byte)(index / 255), (byte)(index % 255),
        0x55,
    ];

    /// <summary>帧#5 速度；浏览分类=全灯滚动时上限 70（厂商语义，min(v,70)）。</summary>
    public static byte[] Speed(int v, bool quandengMode = false)
    {
        if (v is < 0 or > 100) throw new ArgumentOutOfRangeException(nameof(v), v, "speed out of range");
        return [0xBC, 0x08, 0x01, (byte)(quandengMode ? Math.Min(v, 70) : v), 0x55];
    }

    /// <summary>帧#6 律动变色（实时 HSV）——大端 16 位 /255（与静态调色 /256 不同，勿统一）。
    /// 与 Android 版一致：无域校验（休眠接口，负值编码行为亦逐字节对齐，勿擅自加 guard）。</summary>
    public static byte[] ReactiveColor(int h, float s)
    {
        var sv = Math.Clamp((int)(s * 1000), 0, 1000);
        return
        [
            0xBC, 0x09, 0x06,
            (byte)(h / 255), (byte)(h % 255),
            (byte)(sv / 255), (byte)(sv % 255),
            0, 0, 0x55,
        ];
    }

    /// <summary>帧#7 模式页灵敏度。</summary>
    public static byte[] Sensitivity(int v)
    {
        if (v is < 0 or > 100) throw new ArgumentOutOfRangeException(nameof(v), v, "sensitivity out of range");
        return Bc5(0x10, 0x01, v);
    }

    /// <summary>帧#8 拾音源：手机麦=01、灯载麦=00。</summary>
    public static byte[] MicSource(bool phoneMic) => Bc5(0x0F, 0x01, phoneMic ? 0x01 : 0x00);

    /// <summary>帧#9 拾音类型：经典=1、柔和=2、动感=3、迪斯科=4。</summary>
    public static byte[] MicType(int type)
    {
        if (type is < 1 or > 4) throw new ArgumentOutOfRangeException(nameof(type), type, "type out of range");
        return Bc5(0x11, 0x01, type);
    }

    /// <summary>帧#10 拾音灵敏度。</summary>
    public static byte[] MicSensitivity(int v)
    {
        if (v is < 0 or > 100) throw new ArgumentOutOfRangeException(nameof(v), v, "mic sensitivity out of range");
        return Bc5(0x12, 0x01, v);
    }

    // ━━━ 7E 族（头 0x7E 尾 0xEF，9 字节）━━━

    /// <summary>帧#11 RGB 直发（麦克风 RGB 模式）。</summary>
    public static byte[] RgbDirect(int r, int g, int b) =>
    [
        0x7E, 0x07, 0x05, 0x03,
        (byte)r, (byte)g, (byte)b,
        0, 0xEF,
    ];

    /// <summary>帧#12 定时设置（24 小时制；open=true 开灯定时；weekBits bit0=周一，真机仲裁项）。</summary>
    public static byte[] TimerSet(int hour, int minute, bool open, int weekBits)
    {
        if (hour is < 0 or > 23 || minute is < 0 or > 59)
            throw new ArgumentOutOfRangeException(nameof(hour), $"{hour}:{minute} out of range");
        return
        [
            0x7E, 0x08, 0x82,
            (byte)hour, (byte)minute, 0,
            (byte)(open ? 0 : 1),
            (byte)weekBits,
            0xEF,
        ];
    }

    /// <summary>帧#13 定时查询（发送后 GATT read 读回）。</summary>
    public static byte[] TimerQuery(bool open) =>
    [
        0x7E, 0x08, 0x82,
        0xFF, 0xFF, 0xFF,
        (byte)(open ? 0 : 1),
        0,
        0xEF,
    ];

    /// <summary>帧#14 时间同步（weekday：周日=0..周六=6，与 java.util.Calendar.DAY_OF_WEEK-1 对齐）。</summary>
    public static byte[] TimeSync(int hour, int minute, int second, int weekdaySun0) =>
    [
        0x7E, 0x07, 0x83,
        (byte)hour, (byte)minute, (byte)second,
        (byte)weekdaySun0, 0xFF,
        0xEF,
    ];

    /// <summary>
    /// §3 定时读回解析：[3]=时（0xFF=未设置）、[4]=分、[7]=周掩码。
    /// 收紧口径与 Android 一致：长度恰 9 + 设定时数值域校验；不校验帧头尾（响应包络未实证）。
    /// </summary>
    public static TimerInfo? ParseTimerRead(ReadOnlySpan<byte> data)
    {
        if (data.Length != 9) return null;
        int hour = data[3];
        int minute = data[4];
        int weekBits = data[7];
        if (hour == 0xFF) return new TimerInfo(IsSet: false, Hour: -1, minute, weekBits);
        if (hour is < 0 or > 23 || minute is < 0 or > 59 || weekBits is < 0 or > 127) return null;
        return new TimerInfo(IsSet: true, hour, minute, weekBits);
    }

    public readonly record struct TimerInfo(bool IsSet, int Hour, int Minute, int WeekBits)
    {
        public bool WeekEnabled(int dayIndex0Mon) => (WeekBits >> dayIndex0Mon & 1) == 1;
    }

    // ━━━ 工具 ━━━

    private static byte[] Bc5(int cmd, int sub, int value) =>
        [0xBC, (byte)cmd, (byte)sub, (byte)value, 0x55];

    /// <summary>帧十六进制串（BLE-WRITE 日志格式，与原厂 logcat/Android 端对齐）。</summary>
    public static string Hex(ReadOnlySpan<byte> bytes) => Convert.ToHexString(bytes);
}
