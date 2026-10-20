package io.github.deserthouse.quartzlamp.data

/**
 * 153 个灯效（原版名保留——2026-10-15 拍板）。
 * 结构实证（mode.setData 反汇编）：9 个 tab × 17 个色板，
 * 列表 k 装 cmode[k*17+1 .. k*17+17]；设备索引 = cmode 编号（1 起，initView 默认发 1）。
 */
object Effects {
    val categories: List<String> = listOf(
        "单向", "闭幕", "单向跌落", "闭幕跌落", "双向",
        "双向跌落", "单向滚动", "单向跑动", "全灯滚动"
    )

    /** effects[tab][i] = 效果名；设备索引 = tab * 17 + i + 1 */
    val effects: List<List<String>> = listOf(
        listOf("蓝绿跳动", "紫色跳动", "蓝白跳动", "七彩流水跳动", "黄色跳动", "红白跳动", "绿白跳动", "绿紫跳动", "黄紫跳动", "红青跳动", "红色跳动", "青白跳动", "紫白跳动", "黄白跳动", "蓝底黄色跳动", "蓝底绿色跳动", "蓝底白色跳动"),
        listOf("蓝绿闭幕", "紫色闭幕", "蓝白闭幕", "七彩流水闭幕", "红白闭幕", "黄色闭幕", "绿白闭幕", "绿紫闭幕", "黄紫闭幕", "红青闭幕", "红色闭幕", "青白闭幕", "紫白闭幕", "黄白闭幕", "蓝底黄色闭幕", "蓝底绿色闭幕", "蓝底白色闭幕"),
        listOf("白点、蓝绿跳动", "红点、紫色跳动", "白点、蓝白跳动", "红点、七彩跳动", "白点、红白跳动", "白点、黄色跳动", "红点、绿白跳动", "白点、绿紫跳动", "红点、黄紫跳动", "白点、红青跳动", "红点、红色跳动", "白点、青白跳动", "白点、紫色跳动", "白点、黄白跳动", "红点、蓝底黄色跳动", "白点、蓝底绿色跳动", "红点、蓝底白色跳动"),
        listOf("白点、蓝绿闭幕", "红点、紫色闭幕", "白点、蓝白闭幕", "红点、七彩闭幕", "白点、红白闭幕", "白点、黄色闭幕", "红点、绿白闭幕", "白点、绿紫闭幕", "红点、黄紫闭幕", "白点、红青闭幕", "红点、红色闭幕", "白点、青白闭幕", "白点、紫色闭幕", "白点、黄白闭幕", "红点、蓝底黄色闭幕", "白点、蓝底绿色闭幕", "红点、蓝底白色闭幕"),
        listOf("蓝绿双向跳动", "紫色双向跳动", "蓝白双向跳动", "七彩流水双向跳动", "黄色双向跳动", "红白双向跳动", "绿白双向跳动", "绿紫双向跳动", "黄紫双向跳动", "红青双向跳动", "红色双向跳动", "青白双向跳动", "紫白双向跳动", "黄白双向跳动", "蓝底黄色双向跳动", "蓝底绿色双向跳动", "蓝底白色双向跳动"),
        listOf("白点、蓝绿", "红点、紫色", "白点、蓝白", "红点、七彩", "白点、黄色", "白点、红白", "红点、绿白", "白点、绿紫", "红点、黄紫", "白点、红青", "红点、红色", "白点、青白", "白点、紫色", "白点、黄白", "红点、蓝底黄色", "白点、蓝底绿色", "红点、蓝底白色"),
        listOf("白点滚动、蓝绿跳动", "红点滚动、紫色跳动", "白点滚动、蓝白跳动", "红点滚动、七彩跳动", "白点滚动、黄色跳动", "白点滚动、红白跳动", "红点滚动、绿白跳动", "白点滚动、绿紫跳动", "红点滚动、黄紫跳动", "白点滚动、红青跳动", "红点滚动、红色跳动", "白点滚动、青白跳动", "白点滚动、紫色跳动", "白点滚动、黄白跳动", "红点滚动、蓝底黄色跳动", "白点滚动、蓝底绿色跳动", "红点滚动、蓝底白色跳动"),
        listOf("白点跑动、蓝绿跳动", "红点跑动、紫色跳动", "白点跑动、蓝白跳动", "红点跑动、七彩跳动", "白点跑动、黄色跳动", "白点跑动、红白跳动", "红点跑动、绿白跳动", "白点跑动、绿紫跳动", "红点跑动、黄紫跳动", "白点跑动、红青跳动", "红点跑动、红色跳动", "白点跑动、青白跳动", "白点跑动、紫色跳动", "白点跑动、黄白跳动", "红点跑动、蓝底黄色跳动", "白点跑动、蓝底绿色跳动", "红点跑动、蓝底白色跳动"),
        listOf("蓝绿", "紫色", "蓝白", "七彩流水", "红白", "黄色", "绿白", "绿紫", "黄紫", "红青", "红色", "青白", "紫白", "黄白", "蓝底黄色", "蓝底绿色", "蓝底白色"),
    )

    const val TOTAL = 153

    fun deviceIndex(tab: Int, position: Int): Int = tab * 17 + position + 1

    fun nameOf(deviceIndex: Int): String {
        require(deviceIndex in 1..TOTAL)
        return effects[(deviceIndex - 1) / 17][(deviceIndex - 1) % 17]
    }

    // ━━━ tab 内二级分类（N5，用户分类法；词法推导，索引/帧零涉及）━━━

    /**
     * 分类法（用户 2026-10-21 依真机观察设计）：
     * 单色=单词色名（紫/黄/红…）；单色渐变=X+白；彩色渐变=两非白色并列；
     * 七彩=七彩流水；双色=A 底 B（不带渐变）；纯色=仅第 9 类（全灯滚动）中判坏的
     * 单色/双色项——用户真机实证仅显示纯色（原厂固件行为，功能保留）。
     */
    enum class SubCat { MONO, MONO_GRAD, COLOR_GRAD, RAINBOW, DUO, PURE_BROKEN }

    /** 分组渲染顺序 */
    val SUB_CAT_ORDER = listOf(
        SubCat.MONO, SubCat.MONO_GRAD, SubCat.COLOR_GRAD, SubCat.RAINBOW, SubCat.DUO, SubCat.PURE_BROKEN
    )

    fun subCatLabel(c: SubCat): String = when (c) {
        SubCat.MONO -> "单色"
        SubCat.MONO_GRAD -> "单色渐变"
        SubCat.COLOR_GRAD -> "彩色渐变"
        SubCat.RAINBOW -> "七彩"
        SubCat.DUO -> "双色"
        SubCat.PURE_BROKEN -> "纯色"
    }

    private const val ATOMS = "蓝紫红黄绿青白"
    private val DOT_PREFIXES = listOf("白点滚动、", "红点滚动、", "白点跑动、", "红点跑动、", "白点、", "红点、")

    /** 点前缀（白点滚动、/红点、…）：命中返回前缀本身，否则 null（N7 示意条点色渲染用） */
    fun dotPrefixOf(name: String): String? = DOT_PREFIXES.firstOrNull { name.startsWith(it) }

    fun subCatOf(tab: Int, name: String): SubCat {
        var core = name
        for (p in DOT_PREFIXES) if (core.startsWith(p)) { core = core.substring(p.length); break }
        for (s in listOf("双向跳动", "跳动", "闭幕")) if (core.endsWith(s)) { core = core.removeSuffix(s); break }
        if (core.endsWith("色")) core = core.removeSuffix("色")
        val base = when {
            core == "七彩流水" || core == "七彩" -> SubCat.RAINBOW
            '底' in core -> SubCat.DUO
            core.length == 2 && core.all { it in ATOMS } ->
                if (core[1] == '白') SubCat.MONO_GRAD else SubCat.COLOR_GRAD
            core.length == 1 && core[0] in ATOMS -> SubCat.MONO
            else -> SubCat.COLOR_GRAD // 兜底：153 全量词法验证无未归类项
        }
        return if (tab == 8 && (base == SubCat.MONO || base == SubCat.DUO)) SubCat.PURE_BROKEN else base
    }
}
