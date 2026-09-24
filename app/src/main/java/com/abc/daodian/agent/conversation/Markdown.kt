package com.abc.daodian.agent.conversation

/*
 * 模型正文里的 Markdown。见 DESIGN.md §6.10
 *
 * 不靠提示词去管模型怎么写 —— 它写什么这里认什么。先认最常见的几样：粗体、斜体、删除线、行内代码、链接，
 * 标题、列表、引用、分隔线、表格、代码块。认不出的原样当字画，一个字都不吞。
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

private val HEADING = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
private val HEADING_CLOSE = Regex("(?:^|[ \\t]+)#+[ \\t]*$")
private val RULE = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
private val QUOTE = Regex("^ {0,3}> ?")
private val BULLET = Regex("^([ \\t]*)([-*+])[ \\t]+")
private val ORDERED = Regex("^([ \\t]*)(\\d{1,9})([.)])[ \\t]+")
private val DELIMITER = Regex("^\\|?[ \\t]*:?-+:?[ \\t]*(?:\\|[ \\t]*:?-+:?[ \\t]*)*\\|?$")

/** 流着的末行只有这些：还看不出是什么，先不画 */
private val HOLD = Regex("^(?:[-*+>#|`~_=]{1,3}|\\d{1,9}[.)]?)$")

private class BlockParser(private val tailEnd: Int) {

    /** 原文末尾那行，而且还在流 */
    private fun isTail(l: MdLine) = tailEnd >= 0 && l.end == tailEnd

    private fun holding(l: MdLine) = isTail(l) && HOLD.matches(l.text.trim())

    fun parse(lines: List<MdLine>): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        // 列表每一层的缩进，算嵌套深度用；中间隔了别的块就清掉
        val listIndents = ArrayList<Int>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.blank || holding(line)) {
                i++
                continue
            }
            val bullet = BULLET.find(line.text)
            val ordered = if (bullet == null) ORDERED.find(line.text) else null
            if (bullet == null && ordered == null || RULE.matches(line.text)) listIndents.clear()
            i = when {
                fenceOf(line) != null -> code(lines, i, fenceOf(line)!!, out)
                HEADING.containsMatchIn(line.text) -> heading(lines, i, out)
                RULE.matches(line.text) -> {
                    out += MdBlock.Rule
                    i + 1
                }
                tableStart(lines, i) -> table(lines, i, out)
                QUOTE.containsMatchIn(line.text) -> quote(lines, i, out)
                bullet != null -> listItem(lines, i, bullet, null, listIndents, out)
                ordered != null -> listItem(lines, i, ordered, ordered.groupValues[2] + ordered.groupValues[3], listIndents, out)
                else -> paragraph(lines, i, out)
            }
        }
        return out
    }

    /** 这一行另起一块（打断上面那段话 / 那一项） */
    private fun startsBlock(lines: List<MdLine>, i: Int): Boolean {
        val t = lines[i].text
        return fenceOf(lines[i]) != null || HEADING.containsMatchIn(t) || RULE.matches(t) || QUOTE.containsMatchIn(t) ||
            BULLET.containsMatchIn(t) || ORDERED.containsMatchIn(t) || tableStart(lines, i)
    }

    // ---------------- 段落、标题 ----------------

    private fun paragraph(lines: List<MdLine>, i: Int, out: MutableList<MdBlock>): Int {
        val parts = mutableListOf(lines[i].trimStart())
        var j = i + 1
        while (j < lines.size && !lines[j].blank && !holding(lines[j]) && !startsBlock(lines, j)) {
            parts += lines[j].trimStart()
            j++
        }
        out += MdBlock.Paragraph(inlineLines(parts))
        return j
    }

    private fun heading(lines: List<MdLine>, i: Int, out: MutableList<MdBlock>): Int {
        val line = lines[i]
        val m = HEADING.find(line.text)!!
        var content = line.drop(m.range.last + 1)
        // 「## 标题 ##」尾巴上的那串 # 不要
        HEADING_CLOSE.find(content.text)?.let { content = MdLine(content.text.substring(0, it.range.first), content.src) }
        out += MdBlock.Heading(m.groupValues[1].length, inline(content, open = isTail(line)))
        return i + 1
    }

    // ---------------- 列表 ----------------

    private fun listItem(
        lines: List<MdLine>, i: Int, m: MatchResult, number: String?,
        indents: MutableList<Int>, out: MutableList<MdBlock>
    ): Int {
        val line = lines[i]
        val indent = MdLine(m.groupValues[1], 0).indent
        while (indents.isNotEmpty() && indents.last() > indent) indents.removeAt(indents.lastIndex)
        if (indents.isEmpty() || indents.last() < indent) indents += indent
        val depth = (indents.size - 1).coerceAtMost(MAX_DEPTH)

        val parts = mutableListOf(line.drop(m.range.last + 1))
        var j = i + 1
        while (j < lines.size) {
            val l = lines[j]
            if (l.blank) {
                // 空一行后缩进着的字还算这一项（「1. 标题」空一行「   说明」）
                val next = lines.getOrNull(j + 1)
                if (next != null && !next.blank && next.indent > indent && !holding(next) && !startsBlock(lines, j + 1)) {
                    parts += next.trimStart()
                    j += 2
                    continue
                }
                break
            }
            // 紧跟着的一行不是新块，就是这一项没写完的话（缩进没缩进都算）
            if (holding(l) || startsBlock(lines, j)) break
            parts += l.trimStart()
            j++
        }
        out += MdBlock.ListItem(depth, number, inlineLines(parts))
        return j
    }

    // ---------------- 引用 ----------------

    private fun quote(lines: List<MdLine>, i: Int, out: MutableList<MdBlock>): Int {
        val inner = ArrayList<MdLine>()
        var j = i
        while (j < lines.size) {
            val m = QUOTE.find(lines[j].text) ?: break
            inner += lines[j].drop(m.range.last + 1)
            j++
        }
        out += MdBlock.Quote(BlockParser(tailEnd).parse(inner))
        return j
    }

    // ---------------- 代码块 ----------------

    private class Fence(val char: Char, val len: Int, val indent: Int, val lang: String?)

    private fun fenceOf(l: MdLine): Fence? {
        val t = l.text.trimStart()
        val c = t.firstOrNull() ?: return null
        if (c != '`' && c != '~') return null
        val n = t.takeWhile { it == c }.length
        if (n < 3) return null
        val info = t.substring(n).trim()
        if (c == '`' && info.contains('`')) return null
        return Fence(c, n, l.text.length - t.length, info.substringBefore(' ').ifEmpty { null })
    }

    /** 到收尾围栏为止；没有收尾（还在流、或者模型忘了）就一直到末尾 */
    private fun code(lines: List<MdLine>, i: Int, f: Fence, out: MutableList<MdBlock>): Int {
        val body = ArrayList<MdRun>()
        var j = i + 1
        while (j < lines.size) {
            val l = lines[j]
            val t = l.text.trim()
            j++
            if (t.length >= f.len && t.all { it == f.char }) break
            // 围栏缩进着（列表里的代码块），里面每行也去掉一样深的缩进
            val cut = l.text.takeWhile { it == ' ' }.length.coerceAtMost(f.indent)
            body += MdRun(l.text.substring(cut), l.src + cut, MdStyle.CODE)
        }
        // 还在流：末行可能是半截收尾围栏「``」，别当代码画出来
        body.lastOrNull()?.let { last ->
            val t = last.text.trim()
            if (tailEnd >= 0 && last.src + last.text.length == tailEnd && t.isNotEmpty() && t.all { it == f.char }) body.removeAt(body.lastIndex)
        }
        out += MdBlock.Code(f.lang, body)
        return j
    }

    // ---------------- 表格 ----------------

    /** 这一行是表头：下一行是分隔行。还在流、表头是末行时先当它是 */
    private fun tableStart(lines: List<MdLine>, i: Int): Boolean {
        val head = lines[i]
        if (!head.text.contains('|')) return false
        val next = lines.getOrNull(i + 1) ?: return isTail(head) && head.text.trimStart().startsWith('|')
        return delimiter(next, cells(head).size) != null
    }

    /** 分隔行「|---|:--:|」每列的对齐；不是分隔行就是 null。还在流的末行可以是半截（「|---|--」） */
    private fun delimiter(l: MdLine, columns: Int): List<MdAlign>? {
        val t = l.text.trim()
        if (!t.contains('-') || t.any { it !in "|-: \t" }) return null
        val parts = cells(l).map { it.text }
        if (isTail(l)) {
            if (!t.contains('|')) return null
        } else if (!DELIMITER.matches(t) || parts.size != columns) {
            return null
        }
        return List(columns) { k -> alignOf(parts.getOrNull(k)) }
    }

    private fun alignOf(s: String?): MdAlign {
        val t = s?.trim().orEmpty()
        val left = t.startsWith(':')
        val right = t.length > 1 && t.endsWith(':')
        return when {
            left && right -> MdAlign.CENTER
            right -> MdAlign.RIGHT
            left -> MdAlign.LEFT
            else -> MdAlign.NONE
        }
    }

    private fun table(lines: List<MdLine>, i: Int, out: MutableList<MdBlock>): Int {
        val header = cells(lines[i])
        val n = header.size
        val delim = lines.getOrNull(i + 1)
        val align = delim?.let { delimiter(it, n) } ?: List(n) { MdAlign.NONE }
        var j = if (delim != null) i + 2 else i + 1
        val rows = ArrayList<List<List<MdRun>>>()
        while (j < lines.size && !lines[j].blank && lines[j].text.contains('|') && !holding(lines[j])) {
            val cs = cells(lines[j])
            rows += List(n) { k -> cs.getOrNull(k)?.let { inline(it, open = isTail(lines[j])) }.orEmpty() }
            j++
        }
        out += MdBlock.Table(align, header.map { inline(it, open = isTail(lines[i])) }, rows)
        return j
    }

    /** 一行拆成格：去掉首尾的「|」，按没转义、不在 `代码` 里的「|」切开 */
    private fun cells(l: MdLine): List<MdLine> {
        var t = l.trimmed()
        if (t.text.startsWith('|')) t = t.drop(1)
        if (t.text.endsWith('|') && !t.text.endsWith("\\|")) t = MdLine(t.text.dropLast(1), t.src)
        val out = ArrayList<MdLine>()
        var start = 0
        var k = 0
        var inCode = false
        while (k < t.text.length) {
            val c = t.text[k]
            when {
                c == '\\' -> k++
                c == '`' -> inCode = !inCode
                c == '|' && !inCode -> {
                    out += MdLine(t.text.substring(start, k), t.src + start).trimmed()
                    start = k + 1
                }
            }
            k++
        }
        out += MdLine(t.text.substring(start.coerceAtMost(t.text.length)), t.src + start).trimmed()
        return out
    }

    // ---------------- 行内 ----------------

    /** 行尾的空白不算；末行还在流的话按没写完解析 */
    private fun inline(l: MdLine, open: Boolean = isTail(l)): List<MdRun> =
        InlineParser(l.text.trimEnd(), l.src, open).parse()

    /** 几行连成一段，行与行之间一个换行 */
    private fun inlineLines(parts: List<MdLine>): List<MdRun> {
        val out = ArrayList<MdRun>()
        parts.forEachIndexed { k, p ->
            if (k > 0) out += MdRun("\n", p.src - 1)
            out += inline(p)
        }
        return out
    }

    private companion object {
        const val MAX_DEPTH = 3
    }
}

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>\"'"

private class InlineParser(private val t: String, private val base: Int, private val open: Boolean) {

    private val out = ArrayList<MdRun>()

    fun parse(): List<MdRun> {
        span(0, t.length, 0, null)
        return out
    }

    private fun emit(s: String, at: Int, style: Int, link: String?) {
        if (s.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.style == style && last.link == link && last.src + last.text.length == base + at) {
            out[out.lastIndex] = last.copy(text = last.text + s)
        } else {
            out += MdRun(s, base + at, style, link)
        }
    }

    private fun span(from: Int, to: Int, style: Int, link: String?) {
        var i = from
        var plain = from
        fun flush(at: Int) {
            if (at > plain) emit(t.substring(plain, at), plain, style, link)
        }
        // 这一段一直到末行结尾、而且还在流：没闭合的标记先当已经闭合了
        val tail = open && to == t.length
        while (i < to) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < to && t[i + 1] in ESCAPABLE -> {
                    flush(i)
                    emit(t[i + 1].toString(), i + 1, style, link)
                    i += 2
                    plain = i
                }

                c == '`' -> {
                    val n = runOf(c, i, to)
                    val close = closingTicks(i + n, to, n)
                    when {
                        close >= 0 -> {
                            flush(i)
                            code(i + n, close, style, link)
                            i = close + n
                            plain = i
                        }
                        tail -> {
                            flush(i)
                            code(i + n, to, style, link)
                            i = to
                            plain = i
                        }
                        else -> i += n
                    }
                }

                c == '*' || c == '_' || c == '~' -> {
                    val n = runOf(c, i, to)
                    // 单个「~」是「5~10 元」，不是删除线
                    val k = when {
                        c == '~' -> if (n >= 2) 2 else 0
                        n >= 2 -> 2
                        else -> 1
                    }
                    val close = if (k > 0 && canOpen(c, i, n, to)) closer(c, k, i + k + 1, to) else -1
                    val flag = when {
                        c == '~' -> MdStyle.STRIKE
                        k == 2 -> MdStyle.BOLD
                        else -> MdStyle.ITALIC
                    }
                    when {
                        close >= 0 -> {
                            flush(i)
                            span(i + k, close, style or flag, link)
                            i = close + k
                            plain = i
                        }
                        // 「**粗」还没等到收尾：先按粗体画，「**」不露出来
                        tail && k == 2 && canOpen(c, i, n, to) -> {
                            flush(i)
                            span(i + k, to, style or flag, link)
                            i = to
                            plain = i
                        }
                        // 末尾刚冒出来的一串标记符，等下一段字来了才知道是什么
                        tail && i + n == to -> {
                            flush(i)
                            i = to
                            plain = i
                        }
                        else -> i += n
                    }
                }

                c == '[' -> {
                    val close = t.indexOf(']', i + 1)
                    val end = if (close in (i + 1) until to - 1 && t[close + 1] == '(') t.indexOf(')', close + 2) else -1
                    if (end in 0 until to) {
                        val url = t.substring(close + 2, end).trim().removeSurrounding("<", ">").substringBefore(' ')
                        flush(i)
                        // 只认网页链接；别的（intent: 之类）只留字，不让点
                        span(i + 1, close, style, if (isWebUrl(url)) url else link)
                        i = end + 1
                        plain = i
                    } else {
                        i++
                    }
                }

                c == 'h' && link == null && (t.startsWith("https://", i) || t.startsWith("http://", i)) &&
                    (i == 0 || !t[i - 1].isLetterOrDigit()) -> {
                    var e = i
                    while (e < to && isUrlChar(t[e])) e++
                    while (e > i && t[e - 1] in ".,;:!?)'\"") e--
                    val url = t.substring(i, e)
                    if (url.substringAfter("://").isNotEmpty()) {
                        flush(i)
                        emit(url, i, style, url)
                        i = e
                        plain = i
                    } else {
                        i++
                    }
                }

                // 表格格子里常见的 <br>
                c == '<' && t.regionMatches(i, "<br", 0, 3, ignoreCase = true) -> {
                    val gt = t.indexOf('>', i)
                    if (gt in (i + 3) until to && t.substring(i + 3, gt).trim().let { it.isEmpty() || it == "/" }) {
                        flush(i)
                        emit("\n", i, style, link)
                        i = gt + 1
                        plain = i
                    } else {
                        i++
                    }
                }

                else -> i++
            }
        }
        flush(to)
    }

    /** 行内代码：里面不再认任何标记；两头各有一个空格的话各去掉一个 */
    private fun code(from: Int, to: Int, style: Int, link: String?) {
        var a = from
        var b = to
        if (b - a >= 2 && t[a] == ' ' && t[b - 1] == ' ' && t.substring(a, b).isNotBlank()) {
            a++
            b--
        }
        emit(t.substring(a, b), a, style or MdStyle.CODE, link)
    }

    /** 开头标记后面得紧跟着字；「_」还不能在词中间（snake_case） */
    private fun canOpen(c: Char, i: Int, n: Int, to: Int): Boolean {
        if (i + n >= to || t[i + n].isWhitespace()) return false
        if (c == '_' && i > 0 && t[i - 1].isLetterOrDigit()) return false
        return true
    }

    /**
     * 找收尾标记：前面紧挨着字的一串 [c]。[k] = 2 要至少两个；[k] = 1 不要正好两个的（那是粗体的）。
     * 一串比要的长时取最后几个 —— 「***x***」外层是粗体、里面是斜体。跳过行内代码。
     */
    private fun closer(c: Char, k: Int, from: Int, to: Int): Int {
        var j = from
        while (j < to) {
            when (t[j]) {
                '\\' -> j += 2
                '`' -> {
                    val n = runOf('`', j, to)
                    val cl = closingTicks(j + n, to, n)
                    j = if (cl >= 0) cl + n else j + n
                }
                c -> {
                    val n = runOf(c, j, to)
                    val end = j + n
                    val fits = if (k == 2) n >= 2 else n != 2
                    val wordEnd = c != '_' || end >= t.length || !t[end].isLetterOrDigit()
                    if (fits && !t[j - 1].isWhitespace() && wordEnd) return end - k
                    j = end
                }
                else -> j++
            }
        }
        return -1
    }

    private fun closingTicks(from: Int, to: Int, n: Int): Int {
        var j = from
        while (j < to) {
            if (t[j] == '`') {
                val m = runOf('`', j, to)
                if (m == n) return j
                j += m
            } else {
                j++
            }
        }
        return -1
    }

    private fun runOf(c: Char, i: Int, to: Int): Int {
        var j = i
        while (j < to && t[j] == c) j++
        return j - i
    }

    private fun isUrlChar(c: Char) = c.code in 0x21..0x7E && c !in "<>\"`*"

    private fun isWebUrl(url: String) = url.startsWith("https://") || url.startsWith("http://")
}
