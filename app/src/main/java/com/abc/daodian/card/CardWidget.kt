package com.abc.daodian.card

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.card.data.CardDatabase
import com.abc.daodian.card.render.CardRenderer
import com.abc.daodian.card.spec.CardSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面卡片：一种大小一个（[Card2x2Widget] …），桌面上可以放好几块。画什么看登记、照说明拼（`render/`）。见 DESIGN.md §8.5
 *
 * **这几个类的全类名记在桌面上，发出去之后不能挪、不能改名**（§2.2）。
 *
 * 什么时候重画：桌面叫（`onUpdate`，含 30 分钟一次的兜底）、跨零点 / 改时间 / 换时区、数据变了（`plugin/CardBoard.changed`）、
 * 卡片上点了什么（[CardActionReceiver]）。查库不能在主线程，每条路都是 `goAsync()` + 协程（同提醒小组件 `DaodianWidget`）。
 */
abstract class CardWidget(private val size: CardSize) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) =
        async(this, context) { CardRenderer.render(it, AppWidgetManager.getInstance(it), size, appWidgetIds) }

    /** 他把这一块从桌面上拿掉了：说明留着，能再钉 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) = async(this, context) { app ->
        val dao = CardDatabase.get(app).cardDao()
        appWidgetIds.forEach { dao.unplace(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            // 跨了零点「还剩 92 天」就该是 91、每天清零的数该清了；这几条是受保护广播，静态注册收得到
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                async(this, context) { Cards.refresh(it) }
            // 桌面回话：`Cards.pin` 请它加的那一块加上了，这是它的 id
            ACTION_PINNED -> {
                val cardId = intent.getLongExtra(EXTRA_CARD, -1)
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (cardId < 0 || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                async(this, context) { app ->
                    val dao = CardDatabase.get(app).cardDao()
                    // 这一块头一次重画可能抢先认领过了（多半是同一张）；没有才登记
                    if (dao.byWidget(widgetId) == null && dao.byId(cardId)?.widgetId == null) dao.place(cardId, widgetId)
                    CardRenderer.render(app, AppWidgetManager.getInstance(app), size, intArrayOf(widgetId))
                }
            }
        }
    }

    companion object {
        const val ACTION_PINNED = "com.abc.daodian.card.PINNED"
        const val EXTRA_CARD = "com.abc.daodian.card.ID"

        internal fun async(receiver: BroadcastReceiver, context: Context, block: suspend (Context) -> Unit) {
            val pending = receiver.goAsync()
            val app = context.applicationContext
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    block(app)
                } catch (t: Throwable) {
                    Log.e("Daodian/Card", "卡片没办成", t)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

class Card2x2Widget : CardWidget(CardSize.S2X2)
class Card4x1Widget : CardWidget(CardSize.S4X1)
class Card4x2Widget : CardWidget(CardSize.S4X2)
class Card4x4Widget : CardWidget(CardSize.S4X4)

/** 卡片上的按钮、清单的勾、列表行尾的圈。只接自己发的 PendingIntent */
class CardActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val card = intent.getLongExtra(EXTRA_CARD, -1)
        val action = intent.getStringExtra(EXTRA_DO) ?: return
        if (card < 0) return
        CardWidget.async(this, context) { Cards.act(it, card, action) }
    }

    companion object {
        const val ACTION = "com.abc.daodian.card.ACT"
        const val EXTRA_CARD = "com.abc.daodian.card.CARD"
        const val EXTRA_DO = "com.abc.daodian.card.DO"
    }
}
