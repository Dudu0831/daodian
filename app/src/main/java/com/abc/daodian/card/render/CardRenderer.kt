package com.abc.daodian.card.render

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.SizeF
import com.abc.daodian.BuildConfig
import com.abc.daodian.card.data.CardDatabase
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.shared.navigation.Launch
import java.io.File
import java.time.LocalTime

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
            // 点卡片空白处：进 app 的对话页，接着说这张卡片
            val open = PendingIntent.getActivity(
                context, RC_BASE + widgetId, Launch.intent(context, route = Launch.HOME),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val views = when {
                card == null -> CardViews.blank(context, "还没钉东西", "在对话里说「在桌面放一张……」")
                spec == null -> CardViews.blank(context, card.name, "这张的说明读不出来，在对话里让它重画")
                else -> CardViews.responsive(
                    CardViews.build(context, spec, card.id, spacesOf(manager, widgetId, size).also { trace(context, manager, widgetId, it) })
                        .onEach { (_, v) -> v.setOnClickPendingIntent(android.R.id.background, open) }
                )
            }
            if (card == null || spec == null) views.setOnClickPendingIntent(android.R.id.background, open)
            manager.updateAppWidget(widgetId, views)
        }
    }

    /**
     * 桌面报的尺寸记一行到 `files/card_trace.txt`（只 debug 包；这台 ROM 看不到 logcat）。拖了大小不变时先看这里：
     * 桌面叫没叫重画、给的是哪几种大小。超过 32KB 从头写
     */
    private fun trace(context: Context, manager: AppWidgetManager, widgetId: Int, spaces: List<Space>) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val o = manager.getAppWidgetOptions(widgetId)
            val legacy = listOf(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            ).joinToString("/") { o.getInt(it).toString() }
            val file = File(context.filesDir, "card_trace.txt")
            if (file.length() > 32 * 1024) file.delete()
            file.appendText("${LocalTime.now().withNano(0)} #$widgetId 宽/宽/高/高 $legacy · 画了 ${spaces.joinToString { "${it.width.toInt()}×${it.height.toInt()}" }}\n")
        }
    }

    /**
     * 这一块在桌面上实际多大。Android 12 起桌面给一组（竖屏、横屏各一种，拖大小后跟着变）；
     * 没给就用老的那四个数里竖屏那一种（最窄 × 最高）；都没有按这种大小的标准尺寸
     */
    fun spacesOf(manager: AppWidgetManager, widgetId: Int, size: CardSize): List<Space> {
        val options = manager.getAppWidgetOptions(widgetId)
        val sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            .orEmpty().filter { it.width > 0 && it.height > 0 }.map { Space(it.width, it.height) }.distinct()
        if (sizes.isNotEmpty()) return sizes
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return listOf(if (w > 0 && h > 0) Space(w.toFloat(), h.toFloat()) else Space(size.widthDp.toFloat(), size.heightDp.toFloat()))
    }
}
