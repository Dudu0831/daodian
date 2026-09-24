package com.abc.daodian.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型正文的 Markdown 怎么认：流完的、流到一半的，以及每段字能不能对回原文下标（逐字洇开靠它） */
class MarkdownTest {

    private fun runs(md: String, open: Boolean = false): List<MdRun> =
        (Markdown.parse(md, open).single() as MdBlock.Paragraph).runs

    private fun List<MdRun>.plain() = joinToString("") { it.text }

    private fun cellText(cell: List<MdRun>) = cell.plain()

    /** 去掉标记符之后，每段字都还在原文里原样找得到（换行、转义、<br> 除外） */
    private fun assertMapsBack(md: String, open: Boolean = false) {
        fun check(rs: List<MdRun>) = rs.filter { it.text != "\n" }.forEach {
            assertEquals("run「${it.text}」@${it.src}", it.text, md.substring(it.src, it.src + it.text.length))
        }
        fun walk(bs: List<MdBlock>): Unit = bs.forEach { b ->
            when (b) {
                is MdBlock.Paragraph -> check(b.runs)
                is MdBlock.Heading -> check(b.runs)
                is MdBlock.ListItem -> check(b.runs)
                is MdBlock.Quote -> walk(b.blocks)
                is MdBlock.Code -> check(b.lines)
                is MdBlock.Table -> (listOf(b.header) + b.rows).flatten().forEach(::check)
                is MdBlock.Svg -> check(b.lines)
                MdBlock.Rule -> Unit
            }
        }
        walk(Markdown.parse(md, open))
    }

    // ---------------- 行内 ----------------

    @Test
    fun `bold drops the stars and keeps source offsets`() {
        assertEquals(
            listOf(MdRun("这个月", 0), MdRun("餐饮", 5, MdStyle.BOLD), MdRun("花了", 9)),
            runs("这个月**餐饮**花了")
        )
    }

    @Test
    fun `unclosed bold while streaming is drawn bold without the stars`() {
        assertEquals(listOf(MdRun("看", 0), MdRun("餐饮", 3, MdStyle.BOLD)), runs("看**餐饮", open = true))
        // 流完了还没闭合：那就是字
        assertEquals("看**餐饮", runs("看**餐饮").plain())
    }

    @Test
    fun `a marker that just arrived at the very end waits for the next chunk`() {
        assertEquals("看", runs("看*", open = true).plain())
        assertEquals("看", runs("看**", open = true).plain())
        assertEquals("用", runs("用`", open = true).plain())
        assertEquals("看*", runs("看*").plain())
    }

    @Test
    fun `italic nests with bold`() {
        val rs = runs("*a **b** c*")
        assertEquals("a b c", rs.plain())
        assertTrue(rs.all { it.has(MdStyle.ITALIC) })
        assertEquals(listOf("b"), rs.filter { it.has(MdStyle.BOLD) }.map { it.text })

        val both = runs("***x***")
        assertEquals(listOf(MdRun("x", 3, MdStyle.BOLD or MdStyle.ITALIC)), both)
    }

    @Test
    fun `underscores inside words and lone tildes are just text`() {
        assertEquals(listOf(MdRun("file_name_here", 0)), runs("file_name_here"))
        assertEquals(listOf(MdRun("5~10 元", 0)), runs("5~10 元"))
        assertEquals(listOf(MdRun("3 * 4 * 5", 0)), runs("3 * 4 * 5"))
        assertEquals(listOf(MdRun("删", 2, MdStyle.STRIKE)), runs("~~删~~"))
    }

    @Test
    fun `inline code is not parsed inside`() {
        val rs = runs("用 `list_expenses` 查")
        assertEquals(listOf("用 ", "list_expenses", " 查"), rs.map { it.text })
        assertTrue(rs[1].has(MdStyle.CODE))
        assertEquals(3, rs[1].src)
        assertEquals("**不加粗**", runs("`**不加粗**`").single().text)
    }

    @Test
    fun `escaped markers stay as characters`() {
        val rs = runs("\\*不是粗体\\*")
        assertEquals("*不是粗体*", rs.plain())
        assertTrue(rs.none { it.style != 0 })
    }

    @Test
    fun `links only open web urls`() {
        val rs = runs("看[官网](https://a.com/x)吧")
        assertEquals(listOf("看", "官网", "吧"), rs.map { it.text })
        assertEquals("https://a.com/x", rs[1].link)

        val intent = runs("[点我](intent://evil)")
        assertEquals("点我", intent.plain())
        assertNull(intent.single().link)

        val bare = runs("见 https://a.com/x。")
        assertEquals("https://a.com/x", bare[1].link)
        assertEquals("。", bare[2].text)
    }

    @Test
    fun `br becomes a line break`() {
        assertEquals("一\n二", runs("一<br>二").plain())
        assertEquals("一\n二", runs("一<br/>二").plain())
    }

    // ---------------- 块 ----------------

    @Test
    fun `headings need a space so ledger ids are not headings`() {
        val bs = Markdown.parse("### 一、餐饮\n正文\n\n#12 那笔是理发")
        assertEquals(3, (bs[0] as MdBlock.Heading).level)
        assertEquals("一、餐饮", (bs[0] as MdBlock.Heading).runs.plain())
        assertEquals("正文", (bs[1] as MdBlock.Paragraph).runs.plain())
        assertEquals("#12 那笔是理发", (bs[2] as MdBlock.Paragraph).runs.plain())
    }

    @Test
    fun `paragraphs split on blank lines and keep single newlines`() {
        val bs = Markdown.parse("第一行\n第二行\n\n第二段")
        assertEquals(2, bs.size)
        assertEquals("第一行\n第二行", (bs[0] as MdBlock.Paragraph).runs.plain())
    }

    @Test
    fun `lists nest by indent and ordered items keep their numbers`() {
        val bs = Markdown.parse("- a\n- b\n  - c\n\n1. d\n2. e").map { it as MdBlock.ListItem }
        assertEquals(listOf(0, 0, 1, 0, 0), bs.map { it.depth })
        assertEquals(listOf(null, null, null, "1.", "2."), bs.map { it.number })
        assertEquals(listOf("a", "b", "c", "d", "e"), bs.map { it.runs.plain() })
    }

    @Test
    fun `a list item swallows its continuation lines`() {
        val item = Markdown.parse("1. **餐饮**：1,234.50\n   主要是外卖").single() as MdBlock.ListItem
        assertEquals("餐饮：1,234.50\n主要是外卖", item.runs.plain())
        assertTrue(item.runs.first().has(MdStyle.BOLD))
    }

    @Test
    fun `bold at line start is not a bullet`() {
        assertTrue(Markdown.parse("**总计**：300").single() is MdBlock.Paragraph)
    }

    @Test
    fun `dashes under a line are a rule, not a heading`() {
        val bs = Markdown.parse("上面\n---\n下面")
        assertEquals(listOf(MdBlock.Paragraph::class, MdBlock.Rule::class, MdBlock.Paragraph::class), bs.map { it::class })
    }

    @Test
    fun `table with alignment`() {
        val t = Markdown.parse("| 类别 | 金额 |\n|---|---:|\n| 餐饮 | 1,234.50 |\n| 交通 | 36 |").single() as MdBlock.Table
        assertEquals(listOf(MdAlign.NONE, MdAlign.RIGHT), t.align)
        assertEquals(listOf("类别", "金额"), t.header.map(::cellText))
        assertEquals(listOf(listOf("餐饮", "1,234.50"), listOf("交通", "36")), t.rows.map { r -> r.map(::cellText) })
    }

    @Test
    fun `short rows are padded and bold works in cells`() {
        val t = Markdown.parse("| a | b | c |\n| - | - | - |\n| **x** | y |").single() as MdBlock.Table
        assertEquals(listOf("x", "y", ""), t.rows.single().map(::cellText))
        assertTrue(t.rows.single()[0].single().has(MdStyle.BOLD))
    }

    @Test
    fun `table header alone is a table only while streaming`() {
        val t = Markdown.parse("| 类别 | 金", open = true).single() as MdBlock.Table
        assertEquals(listOf("类别", "金"), t.header.map(::cellText))
        assertTrue(Markdown.parse("| 类别 | 金").single() is MdBlock.Paragraph)

        // 分隔行写了一半
        val half = Markdown.parse("| 类别 | 金额 |\n|---|--", open = true).single() as MdBlock.Table
        assertEquals(2, half.align.size)
    }

    @Test
    fun `pipes without a delimiter row are not a table`() {
        val bs = Markdown.parse("a | b\n---")
        assertEquals(listOf(MdBlock.Paragraph::class, MdBlock.Rule::class), bs.map { it::class })
    }

    @Test
    fun `code fences, closed or not`() {
        val bs = Markdown.parse("```kotlin\nval x = 1\n  y()\n```\n后面")
        val code = bs[0] as MdBlock.Code
        assertEquals("kotlin", code.lang)
        assertEquals(listOf("val x = 1", "  y()"), code.lines.map { it.text })
        assertEquals("后面", (bs[1] as MdBlock.Paragraph).runs.plain())

        val open = Markdown.parse("```\na\n``", open = true).single() as MdBlock.Code
        assertEquals(listOf("a"), open.lines.map { it.text })
        val unclosed = Markdown.parse("```\na\nb").single() as MdBlock.Code
        assertEquals(listOf("a", "b"), unclosed.lines.map { it.text })
    }

    // ---------------- 图 ----------------

    private val chart = """<svg viewBox="0 0 340 200" xmlns="http://www.w3.org/2000/svg">
  <rect x="0" y="0" width="120" height="20" fill="#9E3B2E"/>
</svg>"""

    @Test
    fun `svg fences become drawings`() {
        val bs = Markdown.parse("按类别：\n\n```svg\n$chart\n```\n\n餐饮最多。")
        val svg = bs[1] as MdBlock.Svg
        assertTrue(svg.done)
        assertEquals(chart, svg.source)
        assertEquals("餐饮最多。", (bs[2] as MdBlock.Paragraph).runs.plain())

        // 没写语言、写 xml 的，里面是 <svg 也算；xml 里不是 svg 的还是代码
        assertTrue(Markdown.parse("```\n$chart\n```").single() is MdBlock.Svg)
        assertTrue(Markdown.parse("```xml\n<?xml version=\"1.0\"?>\n$chart\n```").single() is MdBlock.Svg)
        assertTrue(Markdown.parse("```xml\n<config/>\n```").single() is MdBlock.Code)
    }

    @Test
    fun `bare svg without a fence is a drawing too`() {
        val bs = Markdown.parse("看图：\n$chart\n就这样")
        assertEquals(listOf(MdBlock.Paragraph::class, MdBlock.Svg::class, MdBlock.Paragraph::class), bs.map { it::class })
        assertTrue((bs[1] as MdBlock.Svg).done)
    }

    @Test
    fun `a drawing still streaming is not done`() {
        val half = chart.substring(0, chart.indexOf("<rect") + 10)
        val fenced = Markdown.parse("```svg\n$half", open = true).single() as MdBlock.Svg
        assertTrue(!fenced.done)
        val bare = Markdown.parse("好的\n$half", open = true)[1] as MdBlock.Svg
        assertTrue(!bare.done)
        // 「<sv」还看不出是图：先不画，也不当字露出来
        assertEquals(1, Markdown.parse("好的\n<sv", open = true).size)
        assertTrue(Markdown.parse("```\n<sv", open = true).single() is MdBlock.Svg)
    }

    @Test
    fun `quotes hold blocks`() {
        val q = Markdown.parse("> 引用 **重点**\n> 第二行").single() as MdBlock.Quote
        val p = q.blocks.single() as MdBlock.Paragraph
        assertEquals("引用 重点\n第二行", p.runs.plain())
    }

    @Test
    fun `a tail line that is only a marker waits`() {
        assertEquals(1, Markdown.parse("正文\n-", open = true).size)
        assertEquals(1, Markdown.parse("正文\n\n##", open = true).size)
        assertEquals(1, Markdown.parse("正文\n|", open = true).size)
        assertTrue(Markdown.parse("", open = true).isEmpty())
    }

    @Test
    fun `every run maps back to the source`() {
        val samples = listOf(
            "这个月一共花了 **3,456.70**，比上月少 `12%`。",
            "### 分类\n\n1. **餐饮**：1,234.50\n   - 外卖 800\n   - 堂食 434.50\n2. *交通*：36\n\n---\n\n> 注意：~~旧的~~ 新的",
            "| 类别 | 金额 | 笔数 |\n|:--|--:|:-:|\n| 餐饮 | ¥1,234.50 | 23 |\n| 其他 | 400.00 | 1 |",
            "```\ncode here\n```",
            "  - 缩进的列表\n    接着说\n\n看 [这里](https://x.y) 和 https://z.w/a_b",
            "半截的 **粗体",
            "| a | b |\n|---|---|\n| 1 | 2",
            "图：\n```svg\n<svg viewBox=\"0 0 10 10\">\n<text>餐饮</text>\n</svg>\n```\n完",
            "<svg viewBox=\"0 0 10 10\"><rect/></svg>"
        )
        samples.forEach { md ->
            assertMapsBack(md)
            assertMapsBack(md, open = true)
            // 流式时每一个前缀都不能出错
            for (n in md.indices) assertMapsBack(md.substring(0, n), open = true)
        }
    }
}
