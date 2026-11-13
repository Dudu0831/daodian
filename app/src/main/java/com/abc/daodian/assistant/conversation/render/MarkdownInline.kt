package com.abc.daodian.assistant.conversation.render

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>\"'"

internal class InlineParser(private val t: String, private val base: Int, private val open: Boolean) {

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
