namespace QuartzLamp.Protocol;

/// <summary>
/// 153 灯效（原版名保留——2026-10-15 拍板）——对齐 Android data/Effects.kt。
/// 名称与分类的权威数据在 golden/effects.json（由 tests/generate_effects_golden.py 从
/// Kotlin 源解析生成，Python/Kotlin/C# 三重一致校验）；本类型提供规则实现与查询 API。
/// </summary>
public static class Effects
{
    public const int Total = 153;
    public const int TabSize = 17;
    public const int TabCount = 9;

    public enum SubCat { Mono, MonoGrad, ColorGrad, Rainbow, Duo, PureBroken }

    public static readonly IReadOnlyList<string> Categories =
        ["单向", "闭幕", "单向跌落", "闭幕跌落", "双向", "双向跌落", "单向滚动", "单向跑动", "全灯滚动"];

    private const string Atoms = "蓝紫红黄绿青白";
    private static readonly string[] DotPrefixes = ["白点滚动、", "红点滚动、", "白点跑动、", "红点跑动、", "白点、", "红点、"];
    private static readonly string[] ActionSuffixes = ["双向跳动", "跳动", "闭幕"];

    public static int DeviceIndex(int tab, int position) => tab * TabSize + position + 1;

    public static string NameOf(IReadOnlyList<IReadOnlyList<string>> tabs, int deviceIndex)
    {
        if (deviceIndex < 1 || deviceIndex > Total)
            throw new ArgumentOutOfRangeException(nameof(deviceIndex), deviceIndex, "index out of range");
        return tabs[(deviceIndex - 1) / TabSize][(deviceIndex - 1) % TabSize];
    }

    /// <summary>点前缀（白点滚动、/红点、…）：命中返回前缀本身（N7 示意条点色渲染用）。</summary>
    public static string? DotPrefixOf(string name) =>
        DotPrefixes.FirstOrDefault(name.StartsWith);

    /// <summary>tab 内二级分类（N5 词法规则；与 Kotlin subCatOf/Python 生成器三重一致）。</summary>
    public static SubCat SubCatOf(int tab, string name)
    {
        var core = name;
        foreach (var p in DotPrefixes)
        {
            if (core.StartsWith(p)) { core = core[p.Length..]; break; }
        }
        foreach (var s in ActionSuffixes)
        {
            if (core.EndsWith(s)) { core = core[..^s.Length]; break; }
        }
        if (core.EndsWith("色")) core = core[..^1];

        SubCat baseCat;
        if (core is "七彩流水" or "七彩") baseCat = SubCat.Rainbow;
        else if (core.Contains('底')) baseCat = SubCat.Duo;
        else if (core.Length == 2 && core.All(Atoms.Contains))
            baseCat = core[1] == '白' ? SubCat.MonoGrad : SubCat.ColorGrad;
        else if (core.Length == 1 && Atoms.Contains(core[0])) baseCat = SubCat.Mono;
        else baseCat = SubCat.ColorGrad; // 兜底：153 全量词法验证无未归类项

        return tab == 8 && baseCat is SubCat.Mono or SubCat.Duo ? SubCat.PureBroken : baseCat;
    }
}
