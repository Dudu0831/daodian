package com.abc.daodian.card

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.data.CardDatabase
import java.time.LocalDate

/**
 * 桌面卡片的进出口：备一张、请桌面放上去、换它画的那件事、数据变了重画。见 DESIGN.md §8.5
 *
 * 只是展示面：钉的那件事是什么样，每次重画问交它的模块（`plugin/CardSource`），这里不认识提醒、账。
 * 刷新是推的 —— 模块的东西变了喊 `plugin/CardBoard.changed`，落到这里的 [refresh]。
 */
object Cards {

    private const val TAG = "Daodian/Card"

    /** 一种模板对应哪个小组件 */
    fun providerOf(template: CardTemplate): Class<out CardWidget> = when (template) {
        CardTemplate.BIG -> BigCardWidget::class.java
        CardTemplate.BAR -> BarCardWidget::class.java
    }

    /** [asked]：桌面收下了「加一块」的请求（会弹一个框让他点添加）。false = 这个桌面不支持，要他自己去加 */
    data class Pinned(val card: Card, val asked: Boolean)

    /**
     * 备一张卡片，并请桌面把它放上去（`requestPinAppWidget`：桌面弹框，他点「添加」才真的出现）。
     * 得在前台调 —— 对话页、桌面速记开着的时候都算。
     *
     * 桌面回话走 [CardWidget.ACTION_PINNED]，带回那一块的 id；不回话（他手动从小组件列表里加）也行：
     * 新加的那一块头一次重画时认领最新一张还没放的（`CardRenderer`）。同一种模板没放的只留这一张。
     */
    suspend fun pin(context: Context, source: String, ref: Long, template: CardTemplate, start: LocalDate?): Pinned {
        val app = context.applicationContext
        val dao = CardDatabase.get(app).cardDao()
        dao.clearUnplaced(template.name)
        val now = System.currentTimeMillis()
        val draft = Card(source = source, ref = ref, template = template.name, startDay = (start ?: LocalDate.now()).toString(), createdAt = now)
        val card = draft.copy(id = dao.insert(draft))

        val provider = providerOf(template)
        val manager = AppWidgetManager.getInstance(app)
        val asked = runCatching {
            manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
                ComponentName(app, provider), null,
                // MUTABLE：桌面要往里填它给这一块分的 id（EXTRA_APPWIDGET_ID）
                PendingIntent.getBroadcast(
                    app, card.id.toInt(),
                    Intent(app, provider).setAction(CardWidget.ACTION_PINNED).putExtra(CardWidget.EXTRA_CARD, card.id),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }.onFailure { Log.e(TAG, "请桌面加卡片失败", it) }.getOrDefault(false)
        return Pinned(card, asked)
    }

    suspend fun all(context: Context): List<Card> = CardDatabase.get(context.applicationContext).cardDao().all()

    /** 已有的一张换成画另一件事（模板不变：尺寸是桌面上那一块的）。没有这张是 null */
    suspend fun retarget(context: Context, cardId: Long, source: String, ref: Long, start: LocalDate?): Card? {
        val app = context.applicationContext
        val dao = CardDatabase.get(app).cardDao()
        dao.byId(cardId) ?: return null
        dao.retarget(cardId, source, ref, (start ?: LocalDate.now()).toString())
        refresh(app)
        return dao.byId(cardId)
    }

    /** 桌面上所有卡片重画一次。一张都没放就什么都不查 */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        CardTemplate.entries.forEach { template ->
            val ids = manager.getAppWidgetIds(ComponentName(app, providerOf(template)))
            if (ids.isEmpty()) return@forEach
            runCatching { CardRenderer.render(app, manager, template, ids) }
                .onFailure { Log.e(TAG, "重画卡片失败", it) }
        }
    }
}
