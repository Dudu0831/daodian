package com.abc.daodian.assistant.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianFonts
import com.abc.daodian.shared.theme.DaodianPalette
import com.abc.daodian.shared.theme.DaodianType

/** 列表每深一层缩进这么多 */
private val ListIndent = 18.dp

/** 表格一列最宽这么多，再长就在格子里折行；整张表比屏幕宽就横着滑 */
private val MaxColumn = 220.dp

/** 金额、百分比、笔数这类：这一列全是它就靠右、等宽数字 */
private val NUMERIC = Regex("^[+\\-−]?[¥￥$]?\\s*[+\\-−]?[\\d,]+(?:\\.\\d+)?\\s*(?:元|块|%|％|笔|次|个)?$")

/** 粗体的标记：画完字之后在这几段底下垫「划重点」那道淡墨 */
private const val MARK = "mark"

/** 一个回合的正文分不分层。见 DESIGN.md §6.7「分层」 */
enum class ProseTone {
    /** 不分层，整块照传进来的字样画 */
    Plain,
    /** 回合的第一块正文：第一段（连同它前面的标题）照原样，后面的退一档 */
    Lead,
    /** 回合里后来的正文：整块退一档 */
    Rest
}

/**
 * 模型说的话，按 Markdown 画。怎么认在 Markdown.kt，见 DESIGN.md §6.7。
 *
 * 流着的时候和 [InkText] 一样逐字洇开、末尾跟光标：到货时刻按原文下标记（[rememberInk]），
 * 去掉标记符后的字拿自己的原文下标（[MdRun.src]）去查。流完了就是普通的几段 Text。
 * 样式守墨宋的规矩（DESIGN.md §8.1）：标题靠宋体和字号、不靠加粗；朱砂只给链接；卡片圆角 5dp。
 * 粗体不加粗，是底下一道淡墨的「划重点」；金额、日期、钟点换宋体、墨色。
 * [tone] 分层：第一段之后的字退成 [DaodianType.proseRest]、ink2。
 * 模型画的图（```svg）交给 [SvgBlock]，见 §6.8。
 */
@Composable
fun MarkdownText(
    text: String,
    streaming: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    caret: Boolean = false,
    tone: ProseTone = ProseTone.Plain
) {
    val colors = DaodianColors.current
    val ink = rememberInk(text, streaming)
    val blocks = remember(text, streaming) { Markdown.parse(text, open = streaming) }
    val lead = MdLook(style, color, colors)
    val rest = MdLook(DaodianType.proseRest, colors.ink2, colors)
    if (blocks.isEmpty()) {
        if (caret) Text(buildAnnotatedString { appendCaret(color, ink) }, modifier, style = style)
        return
    }
    val leadCount = leadCountOf(blocks)
    val lookOf = { i: Int ->
        when (tone) {
            ProseTone.Plain -> lead
            ProseTone.Lead -> if (i < leadCount) lead else rest
            ProseTone.Rest -> rest
        }
    }
    Column(modifier) { MdBlocks(blocks, lookOf, ink, caret) }
}

/** 第一段：从头数到第一个段落（含）。前面是标题的，标题算在第一段里；一个段落都没有就整块都是 */
internal fun leadCountOf(blocks: List<MdBlock>): Int =
    blocks.indexOfFirst { it is MdBlock.Paragraph }.let { if (it < 0) blocks.size else it + 1 }

/** 一套字样和颜色。引用里的字换成 [DaodianPalette.ink2]，其余照旧；标题、划重点、数字一律墨色 */
private class MdLook(val body: TextStyle, val color: Color, val colors: DaodianPalette) {

    fun heading(level: Int): TextStyle =
        if (level <= 2) body.copy(fontFamily = DaodianFonts.serif, fontSize = 18.sp, lineHeight = 28.sp)
        else body.copy(fontFamily = DaodianFonts.serif, fontSize = 16.sp, lineHeight = 26.sp)

    val code = TextStyle(fontFamily = DaodianFonts.mono, fontSize = 12.5.sp, lineHeight = 19.sp)
    val cell = DaodianType.bodySmall
    val headCell = DaodianType.caption.copy(lineHeight = 19.sp)

    fun quoted() = MdLook(body, colors.ink2, colors)

    /** 一串 [MdRun] 拼成带样式的字；[base] 是这段字的本色（表头是 muted） */
    fun inline(runs: List<MdRun>, ink: InkFrame?, caret: Boolean, base: Color = color): AnnotatedString = buildAnnotatedString {
        runs.forEach { r ->
            val bold = r.has(MdStyle.BOLD)
            val tone = when {
                r.link != null -> colors.accent
                r.has(MdStyle.STRIKE) -> colors.muted
                bold -> colors.ink
                else -> base
            }
            val code = r.has(MdStyle.CODE)
            val span = SpanStyle(
                color = if (tone != base) tone else Color.Unspecified,
                fontWeight = if (bold) FontWeight.Medium else null,
                fontStyle = if (r.has(MdStyle.ITALIC)) FontStyle.Italic else null,
                textDecoration = if (r.has(MdStyle.STRIKE)) TextDecoration.LineThrough else null,
                fontFamily = if (code) DaodianFonts.mono else null,
                fontSize = if (code) 0.9.em else TextUnit.Unspecified,
                background = if (code) colors.surfaceAlt else Color.Unspecified
            )
            val link = r.link
            if (link != null) {
                withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = colors.accent)))) {
                    withStyle(span) { appendInk(r.text, r.src, tone, ink) }
                }
            } else {
                withStyle(span) {
                    if (bold) pushStringAnnotation(MARK, "")
                    if (code) appendInk(r.text, r.src, tone, ink) else appendData(r.text, r.src, tone, ink)
                    if (bold) pop()
                }
            }
        }
        if (caret) appendCaret(base, ink)
    }

    /** 金额、日期、钟点换宋体、墨色（[Markdown.dataRanges]）；淡色的字（表头、删除线）里的跟着淡 */
    private fun AnnotatedString.Builder.appendData(s: String, src: Int, tone: Color, ink: InkFrame?) {
        val dataTone = if (tone == colors.muted) tone else colors.ink
        var at = 0
        Markdown.dataRanges(s).forEach { r ->
            if (r.first > at) appendInk(s.substring(at, r.first), src + at, tone, ink)
            withStyle(SpanStyle(color = dataTone, fontFamily = DaodianFonts.serif, fontFeatureSettings = "tnum")) {
                appendInk(s.substring(r.first, r.last + 1), src + r.first, dataTone, ink)
            }
            at = r.last + 1
        }
        if (at < s.length) appendInk(s.substring(at), src + at, tone, ink)
    }
}

/** 块与块之间。间距长在后一块的顶上；段与段之间空得比行距明显，一段一段才分得开 */
private fun gapBefore(prev: MdBlock?, b: MdBlock): Dp = when {
    prev == null -> 0.dp
    b is MdBlock.Heading -> 18.dp
    prev is MdBlock.Heading -> 6.dp
    prev is MdBlock.ListItem && b is MdBlock.ListItem -> 4.dp
    prev is MdBlock.Paragraph && b is MdBlock.ListItem -> 8.dp
    else -> 14.dp
}

/** 光标跟在最后一块的最后一个字后面 */
@Composable
private fun MdBlocks(blocks: List<MdBlock>, lookOf: (Int) -> MdLook, ink: InkFrame?, caret: Boolean) {
    blocks.forEachIndexed { i, b ->
        val gap = gapBefore(blocks.getOrNull(i - 1), b)
        MdBlockView(b, if (gap > 0.dp) Modifier.padding(top = gap) else Modifier, lookOf(i), ink, caret && i == blocks.lastIndex)
    }
}

@Composable
private fun MdBlockView(b: MdBlock, modifier: Modifier, look: MdLook, ink: InkFrame?, caret: Boolean) {
    val colors = look.colors
    when (b) {
        is MdBlock.Paragraph -> MarkedText(look.inline(b.runs, ink, caret), modifier, look.body, look.color)

        is MdBlock.Heading -> MarkedText(look.inline(b.runs, ink, caret, colors.ink), modifier, look.heading(b.level), colors.ink)

        is MdBlock.ListItem -> Row(modifier.padding(start = ListIndent * b.depth)) {
            Text(
                b.number ?: bulletOf(b.depth),
                Modifier.widthIn(min = 18.dp).padding(end = 4.dp),
                style = look.body.copy(fontFeatureSettings = "tnum"),
                color = colors.muted
            )
            MarkedText(look.inline(b.runs, ink, caret), Modifier.weight(1f), look.body, look.color)
        }

        // 左边一根细线，和思考过程一个画法
        is MdBlock.Quote -> Column(
            modifier
                .drawBehind { drawLine(colors.rule2, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                .padding(start = 12.dp)
        ) {
            val quoted = look.quoted()
            MdBlocks(b.blocks, { quoted }, ink, caret)
        }

        is MdBlock.Code -> CodeBlock(b, modifier, look, ink, caret)

        is MdBlock.Table -> TableBlock(b, modifier, look, ink, caret)

        // 画不出来就退回代码块，原文不丢
        is MdBlock.Svg -> SvgBlock(b, modifier, streaming = ink != null) {
            CodeBlock(MdBlock.Code("svg", b.lines), Modifier, look, null, false)
        }

        MdBlock.Rule -> Column(modifier) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.rule)
            if (caret) Text(buildAnnotatedString { appendCaret(look.color, ink) }, style = look.body)
        }
    }
}

private fun bulletOf(depth: Int) = when (depth) {
    0 -> "•"
    1 -> "◦"
    else -> "·"
}

/**
 * 一段字，粗体（[MARK]）底下垫一道淡墨，压在字的下半截 —— 像拿笔划了重点，不靠加粗。
 * 系统字体多半只有 400 / 700 两档，加粗在中文里要么看不出、要么一团黑。
 */
@Composable
private fun MarkedText(text: AnnotatedString, modifier: Modifier, style: TextStyle, color: Color) {
    val marks = remember(text) { text.getStringAnnotations(MARK, 0, text.length) }
    val marker = DaodianColors.current.skeleton
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        if (marks.isEmpty()) modifier else modifier.drawBehind {
            layout?.let { l -> marks.forEach { drawMark(l, it.start, it.end, marker) } }
        },
        style = style,
        color = color,
        onTextLayout = { layout = it }
    )
}

/** 一段划重点：逐行画，从基线往上 0.42em 到往下 0.14em */
private fun DrawScope.drawMark(l: TextLayoutResult, start: Int, end: Int, color: Color) {
    if (end <= start || l.lineCount == 0) return
    val em = l.layoutInput.style.fontSize.toPx()
    val pad = 1.dp.toPx()
    val lastLine = minOf(l.getLineForOffset(end - 1), l.lineCount - 1)
    for (line in l.getLineForOffset(start)..lastLine) {
        val s = maxOf(start, l.getLineStart(line))
        val e = minOf(end, l.getLineEnd(line, visibleEnd = true))
        if (e <= s) continue
        val left = l.getBoundingBox(s).left
        val right = l.getBoundingBox(e - 1).right
        val base = l.getLineBaseline(line)
        drawRect(color, Offset(minOf(left, right) - pad, base - 0.42f * em), Size(kotlin.math.abs(right - left) + 2 * pad, 0.56f * em))
    }
}

/** 代码块：一张淡纸，不折行，长了横着滑 */
@Composable
private fun CodeBlock(b: MdBlock.Code, modifier: Modifier, look: MdLook, ink: InkFrame?, caret: Boolean) {
    val colors = look.colors
    val shape = RoundedCornerShape(5.dp)
    val text = buildAnnotatedString {
        b.lines.forEachIndexed { k, r ->
            if (k > 0) append('\n')
            appendInk(r.text, r.src, colors.ink2, ink)
        }
        if (caret) appendCaret(colors.ink2, ink)
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text, style = look.code, color = colors.ink2, softWrap = false)
    }
}

/**
 * 表格：没有框，表头下一道 rule2、行间一道 ruleSoft —— 和记账页的清单一个样子。
 * 一列多宽看这列最长的格（最多 [MaxColumn]）；整张表比屏幕宽就横着滑，右边渐隐提示还有。
 * 没写对齐的列，全是金额 / 数字就靠右、用等宽数字。
 */
@Composable
private fun TableBlock(t: MdBlock.Table, modifier: Modifier, look: MdLook, ink: InkFrame?, caret: Boolean) {
    val colors = look.colors
    val cols = t.header.size
    val rows = remember(t) { listOf(t.header) + t.rows }
    val align = remember(t) {
        List(cols) { c ->
            val numeric = t.rows.isNotEmpty() && t.rows.all { row ->
                row[c].joinToString("") { it.text }.trim().let { it.isEmpty() || NUMERIC.matches(it) }
            } && t.rows.any { row -> row[c].isNotEmpty() }
            t.align[c].takeIf { it != MdAlign.NONE } ?: if (numeric) MdAlign.RIGHT else MdAlign.LEFT
        }
    }
    val scroll = rememberScrollState()
    Box(
        modifier
            .fillMaxWidth()
            .fadeEnd(24.dp, scroll.canScrollForward)
            .horizontalScroll(scroll)
    ) {
        TableGrid(cols, rows.size) { r, c ->
            val head = r == 0
            val a = align[c]
            val base = if (head) colors.muted else look.color
            val style = (if (head) look.headCell else look.cell).let {
                if (a == MdAlign.RIGHT) it.copy(fontFeatureSettings = "tnum") else it
            }
            Box(
                Modifier
                    .drawBehind {
                        if (r < rows.lastIndex) {
                            val y = size.height - 0.5.dp.toPx()
                            drawLine(if (head) colors.rule2 else colors.ruleSoft, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                    }
                    .padding(start = if (c == 0) 0.dp else 14.dp, top = 7.dp, bottom = 7.dp),
                contentAlignment = when (a) {
                    MdAlign.RIGHT -> Alignment.TopEnd
                    MdAlign.CENTER -> Alignment.TopCenter
                    else -> Alignment.TopStart
                }
            ) {
                Text(
                    look.inline(rows[r][c], ink, caret && r == rows.lastIndex && c == cols - 1, base),
                    style = style,
                    color = base,
                    textAlign = when (a) {
                        MdAlign.RIGHT -> TextAlign.End
                        MdAlign.CENTER -> TextAlign.Center
                        else -> TextAlign.Start
                    }
                )
            }
        }
    }
}

/**
 * 表格的网格：先按内在宽度定每列宽、按内在高度定每行高，再把每格量成正好那么大 ——
 * 行线画在格子底边上，一行的格子一样高，线才接得上。[cell] 每次必须正好出一个节点。
 */
@Composable
private fun TableGrid(cols: Int, rows: Int, cell: @Composable (r: Int, c: Int) -> Unit) {
    Layout(content = { for (r in 0 until rows) for (c in 0 until cols) cell(r, c) }) { ms, _ ->
        val cap = MaxColumn.roundToPx()
        val widths = IntArray(cols) { c ->
            (0 until rows).maxOf { r -> ms[r * cols + c].maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(cap)
        }
        val heights = IntArray(rows) { r -> (0 until cols).maxOf { c -> ms[r * cols + c].minIntrinsicHeight(widths[c]) } }
        val placeables = ms.mapIndexed { k, m -> m.measure(Constraints.fixed(widths[k % cols], heights[k / cols])) }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until cols) {
                    placeables[r * cols + c].place(x, y)
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}
