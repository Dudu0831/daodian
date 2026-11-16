package com.abc.daodian.card.render

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import com.abc.daodian.card.data.CardDatabase
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.shared.navigation.Launch

/**
 * 桌面上的几块卡片各画一遍。只读不写别的模块；那一块画哪张卡片看登记（`card.db`），还没登记的先认领一张。见 DESIGN.md §8.5
 */
object CardRenderer {

    /** 和别的小组件的 PendingIntent 错开：提醒小组件拿提醒 id 当 requestCode */
    private const val RC_BASE = 2_000_000

    suspend fun render(context: Context, manager: AppWidgetManager, size: CardSize, widgetIds: IntArray) {
        val dao = CardDatabase.get(context).cardDao()
        widgetIds.forEach { widgetId ->
            // 这一块还没登记：桌面没回话、或者他自己从小组件列表里加的 —— 认领请得最晚的那张没放的
            val card = dao.byWidget(widgetId) ?: dao.claimable(size.key)?.also { dao.place(it.id, widgetId) }
            val spec = card?.let { CardSpecs.read(it.spec) }
            val views = when {
                card == null -> CardViews.blank(context, "还没钉东西", "在对话里说「在桌面放一张……」")
                spec == null -> CardViews.blank(context, card.name, "这张的说明读不出来，在对话里让它重画")
                else -> CardViews.build(context, spec, card.own, card.id)
            }
            // 点卡片空白处：进 app 的对话页，接着说这张卡片
            views.setOnClickPendingIntent(
                android.R.id.background,
                PendingIntent.getActivity(
                    context, RC_BASE + widgetId, Launch.intent(context, route = Launch.HOME),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            manager.updateAppWidget(widgetId, views)
        }
    }
}
