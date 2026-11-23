package io.github.deserthouse.quartzlamp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin ↔ 黄金数据全表对照：`windows/golden/effects.json` 由 `tests/generate_effects_golden.py`
 * 从本 Kotlin 源**独立解析并独立分类**后生成，C# 侧另有逐项对照。
 * 此前 Kotlin 侧只有分布计数与 tab8 名单断言——计数相等可掩盖同类内的错位，
 * "Python/Kotlin/C# 三重一致"缺 Kotlin↔黄金的逐项自动防线，本类补齐。
 *
 * 黄金文件不在检出中（仅 Android 的树）时跳过而非失败。
 */
class EffectsGoldenParityTest {

    /** 极简 JSON 读取（仅本黄金文件用到的结构）；JVM 单测内无 org.json 实现，故不引依赖 */
    private object MiniJson {
        private class P(val s: String) {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun parse(): Any? {
                ws()
                return when (val c = s[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    else -> if (c == 't' && s.startsWith("true", i)) { i += 4; true }
                            else if (c == 'f' && s.startsWith("false", i)) { i += 5; false }
                            else if (c == 'n' && s.startsWith("null", i)) { i += 4; null }
                            else num()
                }
            }
            fun obj(): Map<String, Any?> {
                val m = LinkedHashMap<String, Any?>()
                i++ // {
                ws()
                if (s[i] == '}') { i++; return m }
                while (true) {
                    ws()
                    val k = str()
                    ws(); expect(':')
                    m[k] = parse()
                    ws()
                    if (s[i] == ',') { i++; continue }
                    expect('}'); return m
                }
            }
            fun arr(): List<Any?> {
                val l = ArrayList<Any?>()
                i++ // [
                ws()
                if (s[i] == ']') { i++; return l }
                while (true) {
                    l += parse()
                    ws()
                    if (s[i] == ',') { i++; continue }
                    expect(']'); return l
                }
            }
            fun str(): String {
                expect('"')
                val sb = StringBuilder()
                while (s[i] != '"') {
                    if (s[i] == '\\') {
                        i++
                        when (s[i]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                            else -> sb.append(s[i])
                        }
                    } else sb.append(s[i])
                    i++
                }
                i++
                return sb.toString()
            }
            fun num(): Any {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                return s.substring(st, i).toDouble().let { if (it % 1.0 == 0.0) it.toLong() else it }
            }
            fun expect(c: Char) { if (s[i] != c) error("json: expected '$c' at $i in `${s.substring(i, i + 20)}`"); i++ }
        }

        fun parse(text: String): Any? = P(text).parse()
    }

    private fun golden(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val f = File(dir, "windows/golden/effects.json")
            if (f.isFile && File(dir, "app/src/main/java").isDirectory) return f
            dir = dir?.parentFile
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun strings(x: Any?): List<String> = (x as List<Any?>).map { it as String }

    @Test fun `名称与分类_153项与黄金数据逐项一致`() {
        val file = golden()
        assumeTrue("黄金文件 windows/golden/effects.json 不在检出中，跳过", file != null)
        val doc = MiniJson.parse(file!!.readText()) as Map<String, Any?>
        val tabs = (doc["tabs"] as List<Any?>).map { strings(it) }
        val classes = (doc["classification"] as List<Any?>).map { strings(it) }

        assertEquals(9, tabs.size)
        assertEquals("Kotlin 分类页签数", Effects.categories.size, tabs.size)
        var checked = 0
        for (t in tabs.indices) {
            assertEquals("tab$t 应 17 项", 17, tabs[t].size)
            for (p in tabs[t].indices) {
                val name = tabs[t][p]
                assertEquals("名称 tab$t#$p", Effects.effects[t][p], name)
                assertEquals("分类 tab$t#$p「$name」", classes[t][p], Effects.subCatOf(t, name).name)
                checked++
            }
        }
        assertEquals(Effects.TOTAL, checked)
    }

    @Test
    fun `分类计数_与黄金数据汇总一致`() {
        val file = golden()
        assumeTrue("黄金文件 windows/golden/effects.json 不在检出中，跳过", file != null)
        val doc = MiniJson.parse(file!!.readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val counts = doc["counts"] as Map<String, Any?>

        val actual = Effects.SubCat.entries.associateWith { cat ->
            (0 until tabsCount()).sumOf { t -> Effects.effects[t].count { Effects.subCatOf(t, it) == cat } }
        }
        for ((cat, n) in actual) {
            assertEquals("计数 ${cat.name}", (counts[cat.name] as Long).toInt(), n)
            assertTrue("黄金数据不应出现 Kotlin 侧没有的分类", cat.name in counts)
        }
        assertEquals("黄金数据分类数", counts.size, actual.size)
    }

    private fun tabsCount() = Effects.effects.size
}
