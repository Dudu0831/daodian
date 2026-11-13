package com.abc.daodian.assistant.conversation.render

/*
 * 模型正文里的 Markdown。见 DESIGN.md §6.7
 *
 * 不靠提示词去管模型怎么写 —— 它写什么这里认什么。先认最常见的几样：粗体、斜体、删除线、行内代码、链接，
 * 标题、列表、引用、分隔线、表格、代码块。认不出的原样当字画，一个字都不吞。
 * 模型画的图（```svg 代码块，或者直接写的 <svg>…</svg>）单独成块，交给 WidgetBlock.kt 画，见 DESIGN.md §6.8。
 * 纯 Kotlin、不碰 Compose（画在 MarkdownText.kt），JVM 单测直接测。
 *
 * 去掉标记符之后，每段字仍记着自己在原文里的下标（[MdRun.src]）：逐字洇开记的是原文下标的到货时刻，靠它对上。
 */

/** 一段样式相同的字。[text] 的第 i 个字是原文第 [src] + i 个（换行符例外，见 [MdBlock.Paragraph]） */
data class MdRun(val text: String, val src: Int, val style: Int = 0, val link: String? = null) {
    fun has(flag: Int) = style and flag != 0
}

/** [MdRun.style] 的位 */
object MdStyle {
    const val BOLD = 1
    const val ITALIC = 2
    const val CODE = 4
    const val STRIKE = 8
}

enum class MdAlign { NONE, LEFT, CENTER, RIGHT }

sealed interface MdBlock {
    /** 一段话。段内的换行照原样留着（中文回答里单个换行多半就是想换行），行与行之间是一个「\n」 */
    data class Paragraph(val runs: List<MdRun>) : MdBlock

    data class Heading(val level: Int, val runs: List<MdRun>) : MdBlock

    /** 列表的一项。列表不成组，一项一块；[depth] 从 0 起。[number] 是有序列表原文的「1.」，无序的是 null */
    data class ListItem(val depth: Int, val number: String?, val runs: List<MdRun>) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /** 代码块，一行一段 */
    data class Code(val lang: String?, val lines: List<MdRun>) : MdBlock

    /** 表格。每格是一串 [MdRun]；每行的格数已经补齐 / 截到和表头一样 */
    data class Table(val align: List<MdAlign>, val header: List<List<MdRun>>, val rows: List<List<List<MdRun>>>) : MdBlock

    data object Rule : MdBlock

    /**
     * 模型画的图：```svg 代码块（```xml / ``` 里是 <svg> 的也算），或者直接写的 <svg>…</svg>。
     * [done] = 已经写到 </svg>，能画了；还在流、没写完的先画占位。
     */
    data class Svg(val lines: List<MdRun>, val done: Boolean) : MdBlock {
        val source: String get() = lines.joinToString("\n") { it.text }
    }

    /**
     * 模型写的会动、能点的小组件：```html 代码块（里面不是光一张 <svg> 的）。交给 WidgetBlock.kt 放进 WebView 跑。
     * [done] = 收尾围栏写到了；还在流的先画占位，半截脚本不跑。
     */
    data class Widget(val lines: List<MdRun>, val done: Boolean) : MdBlock {
        val source: String get() = lines.joinToString("\n") { it.text }
    }
}

object Markdown {

    /**
     * [open] = 还在流，原文末尾那行没写完：
     * - 没闭合的粗体、删除线、行内代码先当已经闭合画，标记符不露出来；
     * - 末行只有一两个标记符（「-」「##」「|」「```」前半截）先不画，等下一段字来了再说；
     * - 表头刚到、分隔行还没到，先按表格画。
     * 流完了（false）一律按原文算。
     */
    fun parse(source: String, open: Boolean = false): List<MdBlock> =
        BlockParser(if (open) source.length else -1).parse(linesOf(source))

    /**
     * 一段字里的金额、日期、钟点、百分比，[start, end)。界面拿宋体、墨色画它们，一眼扫得到（DESIGN.md §6.7「分层」）。
     * 只认长得确定的几样：「¥36.50」「32 元」「11月4日」「2026年10月27日」「8:00」「12%」。
     * 「明天」「周三」「4 号」这类不认 —— 认错了满屏宋体，比不认更难看。
     */
    fun dataRanges(text: String): List<IntRange> = DATA.findAll(text).map { it.range }.toList()

    private val DATA = Regex(
        "(?<![\\d.,])(?:" +
            "[¥￥]\\s?\\d[\\d,]*(?:\\.\\d+)?" +
            "|\\d[\\d,]*(?:\\.\\d+)?\\s?(?:元|块钱|块)" +
            "|(?:\\d{4}\\s?年\\s?)?\\d{1,2}\\s?月\\s?\\d{1,2}\\s?[日号]" +
            "|\\d{1,2}[:：]\\d{2}" +
            "|\\d+(?:\\.\\d+)?\\s?[%％]" +
            ")(?!\\d)"
    )

    private fun linesOf(s: String): List<MdLine> {
        val out = ArrayList<MdLine>()
        var start = 0
        while (true) {
            val nl = s.indexOf('\n', start)
            val end = if (nl < 0) s.length else nl
            out += MdLine(s.substring(start, end).removeSuffix("\r"), start)
            if (nl < 0) break
            start = nl + 1
        }
        return out
    }
}

/** 一行（可能已经去掉了引用的「>」、列表的缩进）。[src] 是 [text] 在原文里的起点 */
internal class MdLine(val text: String, val src: Int) {
    val end: Int get() = src + text.length
    val blank: Boolean get() = text.isBlank()

    /** 行首空白有多宽，tab 算 4 */
    val indent: Int
        get() {
            var w = 0
            for (c in text) {
                if (c == ' ') w++ else if (c == '\t') w += 4 else break
            }
            return w
        }

    fun drop(n: Int) = MdLine(text.substring(n), src + n)
    fun trimStart() = drop(text.length - text.trimStart().length)
    fun trimmed() = MdLine(text.trim(), src + text.length - text.trimStart().length)
}
