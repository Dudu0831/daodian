package com.abc.daodian.card.render

import com.abc.daodian.card.spec.Block
import com.abc.daodian.card.spec.ButtonIcon
import com.abc.daodian.card.spec.NumberSize
import com.abc.daodian.card.spec.Ref
import com.abc.daodian.card.spec.TextStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 桌面上那一块实际多大（dp），整块卡片的外框，含内边距 */
data class Space(val width: Float, val height: Float)

/**
 * 照实际大小把一份说明收放一遍（§8.5「跟着大小变」）：模型只写一份说明，桌面上拖大拖小时由这里定
 * 列表、清单显示几行，大数字用多大的字，实在放不下时先收掉哪几块。不碰 Android，单元测试直接跑。
 *
 * RemoteViews 量不了里面的东西，这里按布局里写死的尺寸估（`res/layout/card_*.xml`，改了那边要跟着改这里的数）：
 * 字高按字号 1.35 倍、中文一个字一个字号宽、西文半个。估得粗，宁可少放一行，不让卡片边裁掉半行。
 *
 * 收放的规矩：
 * 1. 竖排里先按「最省」排（列表一行、数字最小号），放不下就按轻重收掉一块：细线、小字、点数、正文、墨条、柱子、按钮、标题，
 *    最后才是数字、列表、清单、走秒；一样重的先收后面的。至少留一块。
 * 2. 剩下的地方先给列表、清单多放几行（到说明里写的 max），再让数字往大一号长（到说明写的 size，没写到 l）；
 *    比标准尺寸高出两成（拖大了）还有富余，就不管说明了：列表、清单接着加到真有的行数，数字接着长到 xl。最后分给套在里面的竖排、横排。
 * 3. 横排里每一块都拿整排的高；宽度先给定宽的（字、数字、按钮、点数、走秒），剩下的平分给其余的。
 */
object CardFit {

    /** 收放要问的两件事：字里的绑定换成值后是什么、列表 / 清单真有几行（没有几行就不给它留地方） */
    interface Look {
        suspend fun text(template: String): String
        suspend fun rows(ref: Ref): Int
    }

    // card_root 的内边距
    private const val PAD_H = 30f
    private const val PAD_V = 25f

    private const val ROW = 26f
    private const val GAP = 8f
    private const val MORE = 15f

    /**
     * [standard] = 说明写的那种大小的标准尺寸。实际比它高出两成以上才算「拖大了」，才不管说明里的 max、size 接着放
     * （标准大小时照说明，这台荣耀上 4×2 实际 183dp、标准 164dp，不算拖大）
     */
    suspend fun fit(body: Block, space: Space, look: Look, standard: Space = space): Block =
        Fitter(look, space.height > standard.height * 1.2f).fit(body, max(space.width - PAD_H, 40f), max(space.height - PAD_V, 20f))

    private class Fitter(val look: Look, val stretched: Boolean) {

        suspend fun fit(b: Block, w: Float, h: Float): Block = when (b) {
            is Block.Column -> column(b, w, h)
            is Block.Row -> row(b, w, h)
            is Block.Number -> number(b, w, h)
            is Block.ListRows -> b.copy(max = rowsFor(b.rows, if (stretched) Int.MAX_VALUE else b.max, h))
            is Block.Checklist -> b.copy(max = rowsFor(b.rows, if (stretched) Int.MAX_VALUE else b.max, h))
            else -> b
        }

        // ---------------- 竖排

        private suspend fun column(c: Block.Column, w: Float, h: Float): Block.Column {
            val kids = c.children.toMutableList()
            // 1. 按最省的排，放不下就收
            while (kids.size > 1 && kids.sumOf { least(it, w).toDouble() } > h) {
                val drop = kids.indices.minWith(compareBy<Int> { weight(kids[it]) }.thenByDescending { it })
                kids.removeAt(drop)
            }
            val least = kids.map { least(it, w) }
            var extra = h - least.sum()
            val out = kids.toMutableList<Block>()

            // 2a. 列表、清单一行一行加
            out.indices.forEach { i ->
                val k = out[i]
                val (ref, want) = when (k) {
                    is Block.ListRows -> k.rows to k.max
                    is Block.Checklist -> k.rows to k.max
                    else -> return@forEach
                }
                val have = look.rows(ref)
                var rows = 1
                val cap = min(want, have)
                while (rows < cap && extra >= ROW) { rows++; extra -= ROW }
                out[i] = when (k) {
                    is Block.ListRows -> k.copy(max = rows)
                    is Block.Checklist -> k.copy(max = rows)
                    else -> k
                }
            }
            // 2b. 数字往大长
            out.indices.forEach { i ->
                val k = out[i] as? Block.Number ?: return@forEach
                val small = number(k, w, least[i])
                val grown = number(k, w, least[i] + extra)
                extra -= numberHeight(grown.size!!) - numberHeight(small.size!!)
                out[i] = grown
            }
            // 2c. 拖大了还有富余：列表、清单接着加到全列出来，数字接着长到最大号
            if (stretched) out.indices.forEach { i ->
                val k = out[i]
                val ref = when (k) { is Block.ListRows -> k.rows; is Block.Checklist -> k.rows; else -> return@forEach }
                val have = look.rows(ref)
                var rows = when (k) { is Block.ListRows -> k.max; is Block.Checklist -> k.max; else -> 0 }
                // 全列出来时底下那行「⋯」也省了
                while (rows < have && extra >= ROW - if (rows + 1 == have) MORE else 0f) { rows++; extra -= ROW - if (rows == have) MORE else 0f }
                out[i] = when (k) { is Block.ListRows -> k.copy(max = rows); is Block.Checklist -> k.copy(max = rows); else -> k }
            }
            if (stretched) out.indices.forEach { i ->
                val k = out[i] as? Block.Number ?: return@forEach
                if (k.size == NumberSize.entries.last()) return@forEach
                val now = numberHeight(k.size!!)
                val grown = number(k, w, now + extra, NumberSize.entries.last())
                extra -= numberHeight(grown.size!!) - now
                out[i] = grown
            }
            // 2d. 套在里面的分剩下的
            val boxes = out.indices.filter { out[it] is Block.Column || out[it] is Block.Row }
            val share = if (boxes.isEmpty()) 0f else max(extra, 0f) / boxes.size
            boxes.forEach { i -> out[i] = fit(out[i], w, least[i] + share) }
            return c.copy(children = out)
        }

        /** 收的时候谁轻：越小越先收 */
        private fun weight(b: Block): Float = when (b) {
            is Block.Rule -> 0f
            is Block.Text -> when (b.style) { TextStyle.CAPTION -> 1f; TextStyle.BODY -> 3f; TextStyle.TITLE -> 7f }
            is Block.Dots -> 2f
            is Block.Bar -> 4f
            is Block.Bars -> 5f
            is Block.Button -> 6f
            is Block.Number, is Block.ListRows, is Block.Checklist, is Block.Timer -> 8f
            is Block.Column, is Block.Row -> 9f
        } + if (b.grow) 0.5f else 0f

        // ---------------- 横排

        private suspend fun row(r: Block.Row, w: Float, h: Float): Block.Row {
            val widths = widths(r, w, h)
            return r.copy(children = r.children.mapIndexed { i, c -> fit(c, widths[i], h) })
        }

        /** 横排里每一块分到多宽：定宽的先拿（数字按 [h] 里放得下的那一号量），剩下的平分给其余的 */
        private suspend fun widths(r: Block.Row, w: Float, h: Float): List<Float> {
            val gaps = GAP * (r.children.size - 1).coerceAtLeast(0)
            val fixed = r.children.map { if (it.grow) null else intrinsicWidth(it, w, h) }
            val flexible = fixed.count { it == null }
            val each = if (flexible == 0) 0f else max(w - gaps - fixed.filterNotNull().sum(), 0f) / flexible
            return fixed.map { it ?: each }
        }

        /** 不撑满的这一块在横排里要多宽；null = 没有自己的宽度，和别的平分 */
        private suspend fun intrinsicWidth(b: Block, w: Float, h: Float): Float? = when (b) {
            is Block.Text -> min(widthOf(look.text(b.text), textSp(b.style)), w)
            is Block.Number -> min(numberWidth(b, number(b, w, h).size!!), w)
            is Block.Button -> when (b.icon) { ButtonIcon.TEXT -> widthOf(look.text(b.label), 12.5f) + 24f; ButtonIcon.CHECK -> 32f; ButtonIcon.MIC -> 40f }
            is Block.Dots -> b.of * if (b.of > 8) 11f else 15f
            is Block.Timer -> 110f
            else -> null
        }

        // ---------------- 一块最省时多高

        private suspend fun least(b: Block, w: Float): Float = when (b) {
            is Block.Column -> b.children.sumOf { least(it, w).toDouble() }.toFloat()
            is Block.Row -> {
                // 数字按最小号量宽度：最省的时候它也是最小号
                val widths = widths(b, w, numberHeight(NumberSize.S))
                b.children.indices.maxOfOrNull { least(b.children[it], widths[it]) } ?: 0f
            }
            is Block.Text -> textHeight(b.style, look.text(b.text), w)
            is Block.Number -> numberHeight(NumberSize.S)
            is Block.Bar -> 14f + if (b.left.isEmpty() && b.right.isEmpty()) 0f else 20f
            is Block.ListRows -> rowsHeight(look.rows(b.rows), 1, b.max)
            is Block.Checklist -> rowsHeight(look.rows(b.rows), 1, b.max)
            is Block.Bars -> 6f + b.height + if (b.labels) 16f else 0f
            is Block.Dots -> 19f
            is Block.Timer -> 46f
            is Block.Button -> if (b.icon == ButtonIcon.MIC) 40f else 30f
            is Block.Rule -> 13f
        }

        /** 列表一共 [have] 行、放 [rows] 行时多高：放不下的、一行都没有的，底下多一行小字 */
        private fun rowsHeight(have: Int, rows: Int, want: Int): Float {
            if (have == 0) return MORE
            val shown = min(rows, min(have, want))
            return shown * ROW + if (have > shown) MORE else 0f
        }

        /** 只有一块列表时，[h] 里放得下几行，最多 [want]（拖大了不限） */
        private suspend fun rowsFor(ref: Ref, want: Int, h: Float): Int {
            val have = look.rows(ref)
            var rows = 1
            while (rows < min(want, have) && rowsHeight(have, rows + 1, want) <= h) rows++
            return rows
        }

        // ---------------- 大数字

        /** [h] 高、[w] 宽里放得下的最大一号，最大到 [cap]（平时是说明写的，没写到 l；拖大了有富余才放到 xl），最小 s */
        private suspend fun number(b: Block.Number, w: Float, h: Float, cap: NumberSize = b.size ?: NumberSize.L): Block.Number {
            val value = look.text(b.value)
            val pick = NumberSize.entries.filter { it <= cap }.reversed().firstOrNull { s ->
                numberHeight(s) <= h && numberWidth(b, s, value) <= w
            } ?: NumberSize.S
            return b.copy(size = pick)
        }

        private suspend fun numberWidth(b: Block.Number, s: NumberSize, value: String? = null): Float {
            val v = value ?: look.text(b.value)
            val before = look.text(b.before)
            val after = look.text(b.after)
            return widthOf(v, s.sp) +
                (if (before.isEmpty()) 0f else widthOf(before, 12f) + 4f) +
                (if (after.isEmpty()) 0f else widthOf(after, 12f) + 4f)
        }
    }

    fun numberHeight(s: NumberSize): Float = s.sp * 1.15f + 8f

    private fun textSp(style: TextStyle) = when (style) { TextStyle.TITLE -> 13.5f; TextStyle.BODY -> 13f; TextStyle.CAPTION -> 10.5f }

    /** 标题一行，正文、小字最多两行（同布局里的 maxLines） */
    fun textHeight(style: TextStyle, text: String, w: Float): Float {
        val sp = textSp(style)
        val most = if (style == TextStyle.TITLE) 1 else 2
        val lines = ceil(widthOf(text, sp) / max(w, 1f)).toInt().coerceIn(1, most)
        return lines * sp * 1.35f
    }

    /** 一段字大概多宽：中文一个字一个字号，西文、数字半个多 */
    fun widthOf(text: String, sp: Float): Float = text.sumOf { (if (it.code < 0x2E80) 0.58 else 1.0) * sp }.toFloat()
}
