using System.Text.Json;
using QuartzLamp.Protocol;
using Xunit;

namespace QuartzLamp.Tests;

/// <summary>
/// 黄金向量测试：期望值取自 Android LampFramesTest 断言与 AVD 走查实发帧（独立于本 C# 实现）。
/// 任一红=双端帧漂移，必须修实现而非改向量。
/// </summary>
public sealed class GoldenVectorsTests
{
    public sealed record VectorCase(
        string Id, string Op, JsonElement Args,
        string? Hex, string? Throws, JsonElement? Expect, bool ExpectNull);

    private static VectorCase[] LoadVectors()
    {
        var path = FindFile("golden_vectors.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var list = new List<VectorCase>();
        foreach (var v in doc.RootElement.GetProperty("vectors").EnumerateArray())
        {
            list.Add(new VectorCase(
                v.GetProperty("id").GetString()!,
                v.GetProperty("op").GetString()!,
                v.GetProperty("args").Clone(),
                v.TryGetProperty("hex", out var h) ? h.GetString() : null,
                v.TryGetProperty("throws", out var t) ? t.GetString() : null,
                v.TryGetProperty("expect", out var e) ? e.Clone() : null,
                v.TryGetProperty("expectNull", out var n) && n.GetBoolean()));
        }
        return list.ToArray();
    }

    private static string FindFile(string name)
    {
        var dir = AppContext.BaseDirectory;
        for (var i = 0; i < 6 && dir is not null; i++)
        {
            var p = Path.Combine(dir, name);
            if (File.Exists(p)) return p;
            var parent = Path.GetDirectoryName(dir.TrimEnd(Path.DirectorySeparatorChar));
            dir = parent;
        }
        throw new FileNotFoundException(name);
    }

    private static byte[] BuildFrame(string op, JsonElement a) => op switch
    {
        "Power" => LampFrames.Power(a.GetProperty("on").GetBoolean()),
        "ColorStatic" => LampFrames.ColorStatic(a.GetProperty("h").GetInt32(), (float)a.GetProperty("s").GetDouble()),
        "Brightness" => LampFrames.Brightness(a.GetProperty("v").GetInt32()),
        "ModeSelect" => LampFrames.ModeSelect(a.GetProperty("index").GetInt32()),
        "Speed" => LampFrames.Speed(a.GetProperty("v").GetInt32(),
            a.TryGetProperty("quandengMode", out var q) && q.GetBoolean()),
        "ReactiveColor" => LampFrames.ReactiveColor(a.GetProperty("h").GetInt32(), (float)a.GetProperty("s").GetDouble()),
        "Sensitivity" => LampFrames.Sensitivity(a.GetProperty("v").GetInt32()),
        "MicSource" => LampFrames.MicSource(a.GetProperty("phoneMic").GetBoolean()),
        "MicType" => LampFrames.MicType(a.GetProperty("type").GetInt32()),
        "MicSensitivity" => LampFrames.MicSensitivity(a.GetProperty("v").GetInt32()),
        "RgbDirect" => LampFrames.RgbDirect(a.GetProperty("r").GetInt32(), a.GetProperty("g").GetInt32(), a.GetProperty("b").GetInt32()),
        "TimerSet" => LampFrames.TimerSet(a.GetProperty("hour").GetInt32(), a.GetProperty("minute").GetInt32(),
            a.GetProperty("open").GetBoolean(), a.GetProperty("weekBits").GetInt32()),
        "TimerQuery" => LampFrames.TimerQuery(a.GetProperty("open").GetBoolean()),
        "TimeSync" => LampFrames.TimeSync(a.GetProperty("hour").GetInt32(), a.GetProperty("minute").GetInt32(),
            a.GetProperty("second").GetInt32(), a.GetProperty("weekdaySun0").GetInt32()),
        _ => throw new InvalidOperationException($"unknown op {op}"),
    };

    public static IEnumerable<object[]> Vectors() => LoadVectors().Select(v => new object[] { v });

    [Theory]
    [MemberData(nameof(Vectors))]
    public void Vector(VectorCase v)
    {
        if (v.Op == "ParseTimerRead")
        {
            var bytes = Convert.FromHexString(v.Args.GetProperty("bytes").GetString()!);
            if (v.ExpectNull)
            {
                Assert.Null(LampFrames.ParseTimerRead(bytes));
                return;
            }
            var info = LampFrames.ParseTimerRead(bytes);
            Assert.NotNull(info);
            Assert.Equal(v.Expect!.Value.GetProperty("isSet").GetBoolean(), info.Value.IsSet);
            Assert.Equal(v.Expect.Value.GetProperty("hour").GetInt32(), info.Value.Hour);
            Assert.Equal(v.Expect.Value.GetProperty("minute").GetInt32(), info.Value.Minute);
            Assert.Equal(v.Expect.Value.GetProperty("weekBits").GetInt32(), info.Value.WeekBits);
            return;
        }

        if (v.Throws is not null)
        {
            Assert.ThrowsAny<ArgumentException>(() => BuildFrame(v.Op, v.Args));
            return;
        }

        var frame = BuildFrame(v.Op, v.Args);
        Assert.Equal(v.Hex, LampFrames.Hex(frame));
    }

    [Fact]
    public void VectorSetCoversAllFrameFamilies()
    {
        var ops = LoadVectors().Select(v => v.Op).ToHashSet();
        foreach (var required in new[] { "Power", "ColorStatic", "Brightness", "ModeSelect", "Speed", "ReactiveColor", "Sensitivity", "MicSource", "MicType", "MicSensitivity", "RgbDirect", "TimerSet", "TimerQuery", "TimeSync", "ParseTimerRead" })
            Assert.True(ops.Contains(required), $"golden set missing family {required}");
    }
}
