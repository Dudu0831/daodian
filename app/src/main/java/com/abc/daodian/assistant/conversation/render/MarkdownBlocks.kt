package com.abc.daodian.assistant.conversation.render

private val HEADING = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
private val HEADING_CLOSE = Regex("(?:^|[ \\t]+)#+[ \\t]*$")
private val RULE = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
private val QUOTE = Regex("^ {0,3}> ?")
private val BULLET = Regex("^([ \\t]*)([-*+])[ \\t]+")
private val ORDERED = Regex("^([ \\t]*)(\\d{1,9})([.)])[ \\t]+")
private val DELIMITER = Regex("^\\|?[ \\t]*:?-+:?[ \\t]*(?:\\|[ \\t]*:?-+:?[ \\t]*)*\\|?$")

private val RAW_SVG = Regex("^\\s*<svg(?:[\\s>]|$)", RegexOption.IGNORE_CASE)

/** 流着的末行只有这些：还看不出是什么，先不画 */
private val HOLD = Regex("^(?:[-*+>#|`~_=]{1,3}|\\d{1,9}[.)]?)$")

/** 「<」「<s」「<sv」：可能是 <svg 的开头 */
private fun svgPrefix(t: String) = t.startsWith('<') && t.length <= 4 && "<svg".startsWith(t, ignoreCase = true)

internal class BlockParser(private val tailEnd: Int) {

    /** 原文末尾那行，而且还在流 */
    private fun isTail(l: MdLine) = tailEnd >= 0 && l.end == tailEnd

    private fun holding(l: MdLine) = isTail(l) && l.text.trim().let { HOLD.matches(it) || svgPrefix(it) }

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
                RAW_SVG.containsMatchIn(line.text) -> rawSvg(lines, i, out)
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
            BULLET.containsMatchIn(t) || ORDERED.containsMatchIn(t) || RAW_SVG.containsMatchIn(t) || tableStart(lines, i)
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
        var closed = false
        while (j < lines.size) {
            val l = lines[j]
            val t = l.text.trim()
            j++
            if (t.length >= f.len && t.all { it == f.char }) {
                closed = true
                break
            }
            // 围栏缩进着（列表里的代码块），里面每行也去掉一样深的缩进
            val cut = l.text.takeWhile { it == ' ' }.length.coerceAtMost(f.indent)
            body += MdRun(l.text.substring(cut), l.src + cut, MdStyle.CODE)
        }
        // 还在流：末行可能是半截收尾围栏「``」，别当代码画出来
        body.lastOrNull()?.let { last ->
            val t = last.text.trim()
            if (tailEnd >= 0 && last.src + last.text.length == tailEnd && t.isNotEmpty() && t.all { it == f.char }) body.removeAt(body.lastIndex)
        }
        out += when {
            isSvg(f.lang, body) -> MdBlock.Svg(body, body.any { it.text.contains("</svg>", ignoreCase = true) })
            f.lang.equals("html", ignoreCase = true) -> MdBlock.Widget(body, closed)
            else -> MdBlock.Code(f.lang, body)
        }
        return j
    }

    /** ```svg 一定是；没写语言、写的 xml / html，看第一行正经内容是不是 <svg（前面的 <?xml、<!-- 跳过） */
    private fun isSvg(lang: String?, body: List<MdRun>): Boolean {
        val l = lang?.lowercase()
        if (l == "svg") return true
        if (l != null && l != "xml" && l != "html") return false
        val first = body.firstOrNull { it.text.isNotBlank() && !it.text.trimStart().startsWith("<?") && !it.text.trimStart().startsWith("<!") }
            ?: return false
        val t = first.text.trim()
        val tail = tailEnd >= 0 && first.src + first.text.length == tailEnd
        return RAW_SVG.containsMatchIn(t) || tail && svgPrefix(t)
    }

    // ---------------- 直接写的 <svg> ----------------

    /** 没包在代码块里的 <svg>…</svg>：一直收到有 </svg> 的那行（中间的空行也算），没有就到末尾 */
    private fun rawSvg(lines: List<MdLine>, i: Int, out: MutableList<MdBlock>): Int {
        val body = ArrayList<MdRun>()
        var j = i
        var done = false
        while (j < lines.size) {
            val l = lines[j]
            body += MdRun(l.text, l.src, MdStyle.CODE)
            j++
            if (l.text.contains("</svg>", ignoreCase = true)) {
                done = true
                break
            }
        }
        out += MdBlock.Svg(body, done)
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
