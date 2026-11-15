package com.abc.daodian.card

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.card.data.CardDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面卡片：一张卡片钉一件事，剩几天由代码每次重画时现算。一种模板一个子类（[BigCardWidget]、[BarCardWidget]），
 * 桌面上可以放好几块。见 DESIGN.md §8.5
 *
 * **这几个类的全类名记在桌面上，发出去之后不能挪、不能改名**（§2.2）。
 *
 * 什么时候重画：桌面叫（`onUpdate`，含 30 分钟一次的兜底）、跨零点 / 改时间 / 换时区、钉的那件事有改动（`plugin/CardBoard.changed`）。
 * 查库不能在主线程，每条路都是 `goAsync()` + 协程（同提醒小组件 `DaodianWidget`）。
 */
abstract class CardWidget(private val template: CardTemplate) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) =
        async(context) { CardRenderer.render(it, AppWidgetManager.getInstance(it), template, appWidgetIds) }

    /** 他把这一块从桌面上拿掉了：登记跟着删 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) = async(context) { app ->
        val dao = CardDatabase.get(app).cardDao()
        appWidgetIds.forEach { dao.deleteByWidget(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            // 跨了零点「还剩 92 天」就该是 91；这几条是受保护广播，静态注册收得到
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                async(context) { Cards.refresh(it) }
            // 桌面回话：`Cards.pin` 请它加的那一块加上了，这是它的 id
            ACTION_PINNED -> {
                val cardId = intent.getLongExtra(EXTRA_CARD, -1)
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (cardId < 0 || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                async(context) { app ->
                    val dao = CardDatabase.get(app).cardDao()
                    // 这一块头一次重画可能抢先认领过了（同一张）；没有才登记
                    if (dao.byWidget(widgetId) == null && dao.byId(cardId)?.widgetId == null) dao.place(cardId, widgetId)
                    CardRenderer.render(app, AppWidgetManager.getInstance(app), template, intArrayOf(widgetId))
                }
            }
        }
    }

    private fun async(context: Context, block: suspend (Context) -> Unit) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                block(app)
            } catch (t: Throwable) {
                Log.e("Daodian/Card", "卡片重画失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PINNED = "com.abc.daodian.card.PINNED"
        const val EXTRA_CARD = "com.abc.daodian.card.ID"
    }
}

/** 2×2 的大数字卡片 */
class BigCardWidget : CardWidget(CardTemplate.BIG)

/** 4×1 的墨条卡片 */
class BarCardWidget : CardWidget(CardTemplate.BAR)
