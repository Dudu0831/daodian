package com.abc.daodian.card.render

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.abc.daodian.R
import com.abc.daodian.card.CardActionReceiver
import com.abc.daodian.card.CardHub
import com.abc.daodian.card.CardReader
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.spec.Align
import com.abc.daodian.card.spec.Block
import com.abc.daodian.card.spec.ButtonIcon
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.NumberSize
import com.abc.daodian.card.spec.Paint
import com.abc.daodian.card.spec.TextStyle
import com.abc.daodian.card.spec.Tone
import com.abc.daodian.card.spec.actions
import com.abc.daodian.card.spec.bindings
import kotlin.math.roundToInt

/**
 * 照说明把零件拼成 RemoteViews。见 DESIGN.md §8.5
 *
 * 每个零件一份布局（`res/layout/card_*.xml`），外面套一层格子（`card_cell_*`）管「撑不撑满」—— RemoteViews 改不了
 * layout_weight，撑满和不撑满只好是两份格子。颜色一律 `setColor` 交 `@color`，桌面切深浅色时才跟着变（§8.2 第 3 条）。
 *
 * 跟着大小变（§8.5）：桌面给的每一种实际大小各画一份（[CardFit] 先收放：列表几行、数字多大、放不下先收哪块），
 * 交给桌面一个「大小 → 画法」的表，拖大拖小时桌面自己挑。RemoteViews 量不了里面的东西，[CardFit] 是估的，偶尔会裁掉一点。
 */
object CardViews {

    /**
     * 照 [spaces] 里每一种大小各画一份（同一次取的数）。[cardId] = 桌面上那一块是哪张卡片，按钮点了发给它；预览是 null，按钮不接。
     * 绑的那条没了（提醒删了）：整张卡画成一行灰字，长按移除 —— 代码删不了桌面上的组件（§8.5）。
     * 返回的每一份都还没挂「点空白处」，由调的一方挂
     */
    suspend fun build(context: Context, spec: CardSpec, cardId: Long?, spaces: List<Space>): Map<Space, RemoteViews> {
        val reader = CardReader(context, CardHub.sources)
        reader.gone(spec.body.bindings() + spec.body.actions())?.let { what -> return spaces.associateWith { gone(context, what) } }
        val look = object : CardFit.Look {
            override suspend fun text(template: String) = reader.text(template)
            override suspend fun rows(ref: com.abc.daodian.card.spec.Ref) = (reader.value(ref) as? CardValue.Rows)?.total ?: 0
        }
        return spaces.associateWith { space ->
            val root = root(context)
            val b = Builder(context, reader, cardId)
            when (val body = CardFit.fit(spec.body, space, look)) {
                is Block.Column -> body.children.forEachIndexed { i, c -> b.add(root, android.R.id.background, c, inRow = false, last = i == body.children.lastIndex) }
                else -> b.add(root, android.R.id.background, body, inRow = false, last = true)
            }
            root
        }
    }

    /** 几份按大小画的交给桌面：一份就直接给，几份给成「大小 → 画法」的表（Android 12 起），桌面拖到哪挑哪份 */
    fun responsive(views: Map<Space, RemoteViews>): RemoteViews =
        views.values.singleOrNull() ?: RemoteViews(views.mapKeys { (s, _) -> android.util.SizeF(s.width, s.height) })

    /**
     * 外壳。桌面刷新时不一定重新铺一遍布局，常常是把这份 RemoteViews 在原来那层上重放一遍（reapply），
     * addView 就接着往后叠 —— 所以先清空，每次都从空的画起
     */
    private fun root(context: Context) = RemoteViews(context.packageName, R.layout.card_root).apply {
        removeAllViews(android.R.id.background)
    }

    /** 绑的那条没了：「绑的那条提醒没了」一行灰字，底下一行「长按移除」 */
    private fun gone(context: Context, what: String): RemoteViews {
        val root = root(context)
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_body).apply {
            setTextViewText(R.id.card_text, "绑的那条${what}没了")
            setColor(R.id.card_text, "setTextColor", R.color.widget_muted)
        })
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_caption).apply { setTextViewText(R.id.card_text, "长按移除") })
        return root
    }

    /** 桌面上还没钉东西、钉的那张不在了：两行字 */
    fun blank(context: Context, title: String, hint: String): RemoteViews {
        val root = root(context)
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_title).apply { setTextViewText(R.id.card_text, title) })
        root.addView(android.R.id.background, RemoteViews(context.packageName, R.layout.card_text_caption).apply { setTextViewText(R.id.card_text, hint) })
        return root
    }

    private class Builder(val context: Context, val reader: CardReader, val cardId: Long?) {

        private val pkg = context.packageName
        private var clicks = 0

        fun views(layout: Int) = RemoteViews(pkg, layout)

        /** 把 [block] 套进格子，加到 [parent] 的 [into] 里 */
        suspend fun add(parent: RemoteViews, into: Int, block: Block, inRow: Boolean, last: Boolean) {
            val cell = views(
                when {
                    inRow && block.grow -> R.layout.card_cell_row_grow
                    inRow -> R.layout.card_cell_row
                    block.grow -> R.layout.card_cell_col_grow
                    else -> R.layout.card_cell_col
                }
            )
            // 横排里零件之间留 8dp
            if (inRow && !last) cell.setViewPadding(R.id.card_cell, 0, 0, dp(8), 0)
            cell.addView(R.id.card_cell, block(block))
            parent.addView(into, cell)
        }

        suspend fun block(b: Block): RemoteViews = when (b) {
            is Block.Column -> views(R.layout.card_column).also { v -> b.children.forEachIndexed { i, c -> add(v, R.id.card_box, c, false, i == b.children.lastIndex) } }
            is Block.Row -> views(R.layout.card_row).also { v -> b.children.forEachIndexed { i, c -> add(v, R.id.card_box, c, true, i == b.children.lastIndex) } }
            is Block.Text -> text(b)
            is Block.Number -> number(b)
            is Block.Bar -> bar(b)
            is Block.ListRows -> list(b)
            is Block.Bars -> bars(b)
            is Block.Checklist -> checklist(b)
            is Block.Dots -> dots(b)
            is Block.Timer -> timer(b)
            is Block.Button -> button(b)
            is Block.Rule -> views(R.layout.card_rule)
        }

        suspend fun text(b: Block.Text): RemoteViews = views(
            when (b.style) { TextStyle.TITLE -> R.layout.card_text_title; TextStyle.CAPTION -> R.layout.card_text_caption; TextStyle.BODY -> R.layout.card_text_body }
        ).apply {
            setTextViewText(R.id.card_text, reader.text(b.text))
            setInt(R.id.card_text, "setGravity", Gravity.CENTER_VERTICAL or when (b.align) { Align.START -> Gravity.START; Align.CENTER -> Gravity.CENTER_HORIZONTAL; Align.END -> Gravity.END })
            setColor(R.id.card_text, "setTextColor", color(b.paint, if (b.style == TextStyle.CAPTION) Tone.MUTED else Tone.INK))
        }

        suspend fun number(b: Block.Number): RemoteViews = views(R.layout.card_number).apply {
            setTextViewText(R.id.card_value, reader.text(b.value))
            // 字号由 CardFit 按实际大小挑好了
            setTextViewTextSize(R.id.card_value, TypedValue.COMPLEX_UNIT_SP, (b.size ?: NumberSize.M).sp)
            setColor(R.id.card_value, "setTextColor", color(b.paint, Tone.INK))
            label(this, R.id.card_before, reader.text(b.before))
            label(this, R.id.card_after, reader.text(b.after))
        }

        suspend fun bar(b: Block.Bar): RemoteViews = views(R.layout.card_bar).apply {
            setProgressBar(R.id.card_ink, 100, (reader.number(b.value) ?: 0).toInt().coerceIn(0, 100), false)
            val left = reader.text(b.left)
            val right = reader.text(b.right)
            setViewVisibility(R.id.card_ends, if (left.isEmpty() && right.isEmpty()) View.GONE else View.VISIBLE)
            setTextViewText(R.id.card_left, left)
            setTextViewText(R.id.card_right, right)
        }

        suspend fun list(b: Block.ListRows): RemoteViews {
            val box = views(R.layout.card_list)
            val value = reader.value(b.rows) as? CardValue.Rows
            val rows = value?.rows.orEmpty()
            rows.take(b.max).forEach { r ->
                box.addView(R.id.card_box, views(R.layout.card_list_row).apply {
                    setTextViewText(R.id.card_row_title, r.title)
                    setTextViewText(R.id.card_row_note, r.note)
                    setViewVisibility(R.id.card_row_note, if (r.note.isEmpty()) View.GONE else View.VISIBLE)
                    setColor(R.id.card_row_title, "setTextColor", if (r.done) R.color.widget_muted else R.color.widget_ink)
                    setColor(R.id.card_row_note, "setTextColor", if (r.accent && !r.done) R.color.widget_accent else R.color.widget_muted)
                    if (r.action != null) {
                        setViewVisibility(R.id.card_row_check, View.VISIBLE)
                        setImageViewResource(R.id.card_row_ring, if (r.done) R.drawable.card_ring_done else R.drawable.widget_done_ring)
                        click(this, R.id.card_row_check, r.action)
                    }
                })
            }
            val more = (value?.total ?: rows.size) - minOf(rows.size, b.max)
            if (rows.isEmpty() || more > 0) {
                box.addView(R.id.card_box, views(R.layout.card_text_caption).apply {
                    setTextViewText(R.id.card_text, if (rows.isEmpty()) "没有" else "还有 $more 条")
                })
            }
            return box
        }

        suspend fun bars(b: Block.Bars): RemoteViews {
            val box = views(R.layout.card_bars)
            val s = reader.value(b.values) as? CardValue.Series ?: return box
            val top = s.values.maxOrNull()?.takeIf { it > 0 } ?: 1
            s.values.forEachIndexed { i, v ->
                box.addView(R.id.card_box, views(R.layout.card_bar_col).apply {
                    setViewLayoutHeight(R.id.card_bar_area, b.height.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                    val future = s.current >= 0 && i > s.current
                    val h = if (future || v <= 0) 1f else (b.height * v.toFloat() / top).coerceAtLeast(2f)
                    setViewLayoutHeight(R.id.card_bar_fill, h, TypedValue.COMPLEX_UNIT_DIP)
                    setInt(
                        R.id.card_bar_fill, "setBackgroundResource",
                        when { future || v <= 0 -> R.drawable.card_fill_line; i == s.current -> R.drawable.card_fill_ink; else -> R.drawable.card_fill_soft }
                    )
                    val label = s.labels.getOrNull(i).orEmpty()
                    setTextViewText(R.id.card_bar_label, label)
                    setViewVisibility(R.id.card_bar_label, if (b.labels && label.isNotEmpty()) View.VISIBLE else View.GONE)
                })
            }
            return box
        }

        suspend fun checklist(b: Block.Checklist): RemoteViews {
            val box = views(R.layout.card_list)
            val rows = (reader.value(b.rows) as? CardValue.Rows)?.rows.orEmpty()
            rows.take(b.max).forEach { r ->
                box.addView(R.id.card_box, views(R.layout.card_check_row).apply {
                    setImageViewResource(R.id.card_check_box, if (r.done) R.drawable.card_box_on else R.drawable.card_box_off)
                    setTextViewText(R.id.card_check_text, r.title)
                    setColor(R.id.card_check_text, "setTextColor", if (r.done) R.color.widget_muted else R.color.widget_ink)
                    r.action?.let { click(this, R.id.card_check_row, it) }
                })
            }
            if (rows.size > b.max) box.addView(R.id.card_box, views(R.layout.card_text_caption).apply { setTextViewText(R.id.card_text, "还有 ${rows.size - b.max} 样") })
            return box
        }

        suspend fun dots(b: Block.Dots): RemoteViews {
            val box = views(R.layout.card_dots)
            val n = (reader.number(b.value) ?: 0).toInt()
            val small = b.of > 8
            repeat(b.of) { i ->
                box.addView(R.id.card_box, views(if (small) R.layout.card_dot_small else R.layout.card_dot).apply {
                    setImageViewResource(R.id.card_dot, if (i < n) R.drawable.card_dot_on else R.drawable.card_dot_off)
                })
            }
            return box
        }

        suspend fun timer(b: Block.Timer): RemoteViews = views(R.layout.card_timer).apply {
            val now = System.currentTimeMillis()
            val until = (reader.value(b.at) as? CardValue.Moment)?.at
            if (until != null && until > now) {
                // 系统自己一秒一秒往下数，我们不用每秒重画。数到 0 那一刻提醒自己响，响完喊重画收掉（在那之前会接着数成负的）
                setChronometer(R.id.card_chrono, SystemClock.elapsedRealtime() + (until - now), null, true)
                setChronometerCountDown(R.id.card_chrono, true)
                setViewVisibility(R.id.card_chrono, View.VISIBLE)
                setViewVisibility(R.id.card_timer_idle, View.GONE)
            } else {
                setTextViewText(R.id.card_timer_idle, "0:00")
                setViewVisibility(R.id.card_chrono, View.GONE)
                setViewVisibility(R.id.card_timer_idle, View.VISIBLE)
            }
        }

        suspend fun button(b: Block.Button): RemoteViews = when (b.icon) {
            ButtonIcon.MIC -> views(R.layout.card_button_mic)
            ButtonIcon.CHECK -> views(R.layout.card_button_check)
            ButtonIcon.TEXT -> views(R.layout.card_button).apply { setTextViewText(R.id.card_button, reader.text(b.label)) }
        }.also { click(it, R.id.card_button, b.action.toString()) }

        suspend fun color(p: Paint, fallback: Tone): Int {
            val tone = when {
                reader.flag(p.accentWhen) -> Tone.ACCENT
                reader.flag(p.mutedWhen) -> Tone.MUTED
                p.base == Tone.INK -> fallback
                else -> p.base
            }
            return when (tone) { Tone.INK -> R.color.widget_ink; Tone.MUTED -> R.color.widget_muted; Tone.ACCENT -> R.color.widget_accent }
        }

        fun label(v: RemoteViews, id: Int, text: String) {
            v.setTextViewText(id, text)
            v.setViewVisibility(id, if (text.isEmpty()) View.GONE else View.VISIBLE)
        }

        /** 点 [id] 做 [action]。每个点击一条不同的 data，PendingIntent 才不会互相盖掉 */
        fun click(v: RemoteViews, id: Int, action: String) {
            val card = cardId ?: return
            val n = clicks++
            v.setOnClickPendingIntent(
                id,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, CardActionReceiver::class.java)
                        .setAction(CardActionReceiver.ACTION)
                        .setData(Uri.parse("daodian-card://$card/$n"))
                        .putExtra(CardActionReceiver.EXTRA_DO, action),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()
    }
}
