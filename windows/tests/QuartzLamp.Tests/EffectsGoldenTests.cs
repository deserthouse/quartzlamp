using System.Text.Json;
using QuartzLamp.Protocol;
using Xunit;

namespace QuartzLamp.Tests;

/// <summary>
/// Effects 数据契约测试：C# 词法实现 vs golden/effects.json（Python 生成器从 Kotlin 源产出）。
/// 153 名称逐项一致 + 分类逐项一致 + 索引公式抽查。
/// </summary>
public sealed class EffectsGoldenTests
{
    private static readonly Dictionary<string, Effects.SubCat> CatMap = new()
    {
        ["MONO"] = Effects.SubCat.Mono,
        ["MONO_GRAD"] = Effects.SubCat.MonoGrad,
        ["COLOR_GRAD"] = Effects.SubCat.ColorGrad,
        ["RAINBOW"] = Effects.SubCat.Rainbow,
        ["DUO"] = Effects.SubCat.Duo,
        ["PURE_BROKEN"] = Effects.SubCat.PureBroken,
    };

    private static JsonElement Load()
    {
        var dir = AppContext.BaseDirectory;
        for (var i = 0; i < 6 && dir is not null; i++)
        {
            var p = Path.Combine(dir, "effects.json");
            if (File.Exists(p)) return JsonDocument.Parse(File.ReadAllText(p)).RootElement.Clone();
            dir = Path.GetDirectoryName(dir.TrimEnd(Path.DirectorySeparatorChar));
        }
        throw new FileNotFoundException("effects.json");
    }

    [Fact]
    public void 名称与分类_153项与黄金数据全一致()
    {
        var doc = Load();
        var tabs = doc.GetProperty("tabs");
        var classes = doc.GetProperty("classification");

        var allTabs = new List<List<string>>();
        for (var t = 0; t < tabs.GetArrayLength(); t++)
        {
            var row = new List<string>();
            foreach (var n in tabs[t].EnumerateArray()) row.Add(n.GetString()!);
            allTabs.Add(row);
        }

        Assert.Equal(9, allTabs.Count);
        Assert.Equal(153, allTabs.Sum(r => r.Count));

        for (var t = 0; t < allTabs.Count; t++)
        {
            for (var p = 0; p < allTabs[t].Count; p++)
            {
                var expected = CatMap[classes[t][p].GetString()!];
                Assert.Equal(expected, Effects.SubCatOf(t, allTabs[t][p]));
            }
        }

        // 名称查询：首/尾/跨界抽查（索引公式独立于分类）
        Assert.Equal("蓝绿跳动", Effects.NameOf(allTabs, 1));
        Assert.Equal("紫色跳动", Effects.NameOf(allTabs, 2));
        Assert.Equal("紫色双向跳动", Effects.NameOf(allTabs, 70)); // tab4 pos1
        Assert.Equal("七彩流水", Effects.NameOf(allTabs, 140));    // tab8 pos3
        Assert.Equal("蓝底白色", Effects.NameOf(allTabs, 153));    // 末项
        Assert.Throws<ArgumentOutOfRangeException>(() => Effects.NameOf(allTabs, 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => Effects.NameOf(allTabs, 154));
    }

    [Fact]
    public void 分类计数_与黄金数据汇总一致()
    {
        var doc = Load();
        var counts = doc.GetProperty("counts");
        Assert.Equal(153, counts.EnumerateObject().Sum(p => p.Value.GetInt32()));
        Assert.Equal(6, counts.GetProperty("PURE_BROKEN").GetInt32()); // tab8 坏项（用户真机实证）
        Assert.Equal(9, counts.GetProperty("RAINBOW").GetInt32());
    }

    [Fact]
    public void 点前缀识别()
    {
        Assert.Equal("白点、", Effects.DotPrefixOf("白点、蓝绿跳动"));
        Assert.Equal("红点滚动、", Effects.DotPrefixOf("红点滚动、紫色跳动"));
        Assert.Null(Effects.DotPrefixOf("紫色跳动"));
    }
}
