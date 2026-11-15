package com.abc.daodian.card

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.abc.daodian.R
import com.abc.daodian.card.data.CardDatabase
import com.abc.daodian.shared.navigation.Launch
import java.time.LocalDate

/**
 * 把一张卡片画成 RemoteViews。只读不写；那件事什么样问交它的模块（`plugin/CardSource`），字是 [CardFace] 算的，这里只管往布局里填。见 DESIGN.md §8.5
 *
 * 颜色一律 `@color` 引用交给桌面解析（`setColor`），深浅色切换时才跟着变（§8.2 第 3 条）。
 */
object CardRenderer {

    /** 和别的小组件的 PendingIntent 错开：提醒小组件拿提醒 id 当 requestCode，同一个 Intent 撞上会互相盖掉 */
    private const val RC_BASE = 2_000_000

    suspend fun render(context: Context, manager: AppWidgetManager, template: CardTemplate, widgetIds: IntArray) {
        val dao = CardDatabase.get(context).cardDao()
        val today = LocalDate.now()
        widgetIds.forEach { widgetId ->
            // 这一块还没登记：认领最新一张备好了没放的（桌面没回话、或者他自己从小组件列表里加的）
            val card = dao.byWidget(widgetId)
                ?: dao.latestUnplaced(template.name)?.also { dao.place(it.id, widgetId) }
            val source = card?.let { CardHub.source(it.source) }
            val subject = if (card != null && source != null) source.subject(context, card.ref) else null
            val views = when {
                card == null -> blank(context, template, "还没钉东西", "在对话里说一句")
                subject == null -> blank(context, template, "这件事不在了", "长按可以移除")
                else -> filled(context, template, CardFace.of(subject, card, today))
            }
            views.setOnClickPendingIntent(
                android.R.id.background,
                PendingIntent.getActivity(
                    context, RC_BASE + widgetId,
                    Launch.intent(context, route = if (card != null && source != null && subject != null) source.route(card.ref) else Launch.HOME),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            manager.updateAppWidget(widgetId, views)
        }
    }

    private fun filled(context: Context, template: CardTemplate, face: CardFace): RemoteViews = when (template) {
        CardTemplate.BIG -> RemoteViews(context.packageName, R.layout.card_big).apply {
            setTextViewText(R.id.card_title, face.title)
            setTextViewText(R.id.card_before, face.before)
            setViewVisibility(R.id.card_before, if (face.before.isEmpty()) View.GONE else View.VISIBLE)
            setTextViewText(R.id.card_number, face.big)
            // 「今天」「办完了」是字不是数，小一号
            setTextViewTextSize(R.id.card_number, TypedValue.COMPLEX_UNIT_SP, if (face.word) 30f else 50f)
            setTextViewText(R.id.card_after, face.after)
            setViewVisibility(R.id.card_after, if (face.after.isEmpty()) View.GONE else View.VISIBLE)
            setTextViewText(R.id.card_foot, face.foot)
            setColor(R.id.card_number, "setTextColor", toneColor(face.tone))
        }
        CardTemplate.BAR -> RemoteViews(context.packageName, R.layout.card_bar).apply {
            setTextViewText(R.id.card_title, face.title)
            setTextViewText(R.id.card_left, face.left)
            setColor(R.id.card_left, "setTextColor", toneColor(face.tone))
            setProgressBar(R.id.card_ink, 100, face.progress, false)
            setViewVisibility(R.id.card_ink, View.VISIBLE)
            setTextViewText(R.id.card_start, face.startLabel)
            setTextViewText(R.id.card_end, face.endLabel)
        }
    }

    /** 没钉东西、钉的那件事被删了：同一份布局，只写两行字 */
    private fun blank(context: Context, template: CardTemplate, title: String, hint: String): RemoteViews = when (template) {
        CardTemplate.BIG -> RemoteViews(context.packageName, R.layout.card_big).apply {
            setTextViewText(R.id.card_title, title)
            setViewVisibility(R.id.card_before, View.GONE)
            setTextViewText(R.id.card_number, "")
            setViewVisibility(R.id.card_after, View.GONE)
            setTextViewText(R.id.card_foot, hint)
        }
        CardTemplate.BAR -> RemoteViews(context.packageName, R.layout.card_bar).apply {
            setTextViewText(R.id.card_title, title)
            setTextViewText(R.id.card_left, "")
            setViewVisibility(R.id.card_ink, View.INVISIBLE)
            setTextViewText(R.id.card_start, hint)
            setTextViewText(R.id.card_end, "")
        }
    }

    private fun toneColor(tone: CardFace.Tone): Int = when (tone) {
        CardFace.Tone.NORMAL -> R.color.widget_ink
        CardFace.Tone.NEAR -> R.color.widget_accent
        CardFace.Tone.GONE -> R.color.widget_muted
    }
}
