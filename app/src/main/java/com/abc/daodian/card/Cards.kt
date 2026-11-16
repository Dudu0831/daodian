package com.abc.daodian.card

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.data.CardDatabase
import com.abc.daodian.card.own.OwnData
import com.abc.daodian.card.render.CardRenderer
import com.abc.daodian.card.render.CardViews
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref
import java.time.LocalDate

/**
 * 桌面卡片的进出口：画一张、改一张、请桌面放上去、改卡片自己的数、桌面上点了什么、数据变了重画。见 DESIGN.md §8.5
 *
 * 只是展示面：钉的那件事什么样、点了「完成」怎么办，问交它的模块（`plugin/CardSource`），这里不认识提醒、账。
 */
object Cards {

    private const val TAG = "Daodian/Card"

    /** 一种大小对应哪个桌面组件 */
    fun providerOf(size: CardSize): Class<out CardWidget> = when (size) {
        CardSize.S2X2 -> Card2x2Widget::class.java
        CardSize.S4X1 -> Card4x1Widget::class.java
        CardSize.S4X2 -> Card4x2Widget::class.java
        CardSize.S4X4 -> Card4x4Widget::class.java
    }

    private fun dao(context: Context) = CardDatabase.get(context.applicationContext).cardDao()

    suspend fun all(context: Context): List<Card> = dao(context).all()

    suspend fun byId(context: Context, id: Long): Card? = dao(context).byId(id)

    /** 新画一张（过了闸门的说明）。还不在桌面上 */
    suspend fun create(context: Context, raw: String, spec: CardSpec): Card {
        val now = System.currentTimeMillis()
        val draft = Card(name = spec.name, size = spec.size.key, spec = raw, own = seed(spec, null), createdAt = now, updatedAt = now)
        return draft.copy(id = dao(context).insert(draft))
    }

    /** 换掉一张的说明。卡片自己记的数照旧（同名的键留着），在桌面上的跟着重画。没有这张是 null */
    suspend fun redraw(context: Context, id: Long, raw: String, spec: CardSpec): Card? {
        val dao = dao(context)
        val old = dao.byId(id) ?: return null
        val now = System.currentTimeMillis()
        dao.redraw(id, spec.name, spec.size.key, raw, now)
        dao.setOwn(id, seed(spec, old.own), now)
        refresh(context)
        return dao.byId(id)
    }

    /** 说明里新定义的 own 先落一份初值，旧的键保留 */
    private fun seed(spec: CardSpec, json: String?): String = OwnData(spec.own, json, LocalDate.now(), System.currentTimeMillis()).json()

    /** [asked]：桌面收下了「加一块」的请求（会弹框，他点「添加」才出现）。false = 这个桌面不支持，要他自己去加 */
    data class Pinned(val card: Card, val asked: Boolean, val already: Boolean = false)

    /**
     * 请桌面把这张放上去（`requestPinAppWidget`）。得在前台调 —— 对话页、桌面速记开着的时候都算。
     * 桌面回话走 [CardWidget.ACTION_PINNED]，带回那一块的 id；不回话（他手动从小组件列表里加）也行：
     * 新加的那一块头一次重画时认领请得最晚的那张（`CardDao.claimable`）
     */
    suspend fun pin(context: Context, id: Long): Pinned? {
        val app = context.applicationContext
        val dao = dao(app)
        val card = dao.byId(id) ?: return null
        if (card.widgetId != null) return Pinned(card, asked = false, already = true)
        val size = CardSize.of(card.size) ?: return null
        dao.askedPin(id, System.currentTimeMillis())
        val provider = providerOf(size)
        val manager = AppWidgetManager.getInstance(app)
        val asked = runCatching {
            manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
                ComponentName(app, provider), null,
                // MUTABLE：桌面要往里填它给这一块分的 id（EXTRA_APPWIDGET_ID）
                PendingIntent.getBroadcast(
                    app, id.toInt(),
                    Intent(app, provider).setAction(CardWidget.ACTION_PINNED).putExtra(CardWidget.EXTRA_CARD, id),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }.onFailure { Log.e(TAG, "请桌面加卡片失败", it) }.getOrDefault(false)
        return Pinned(card, asked)
    }

    /** 改卡片自己记的数，一次几样（[op] 见 `OwnData.apply`）。返回改完的卡片和没改成的原因 */
    suspend fun changeOwn(context: Context, id: Long, changes: List<Triple<String, String, String?>>): Pair<Card, List<String>>? {
        val dao = dao(context)
        val card = dao.byId(id) ?: return null
        val spec = CardSpecs.read(card.spec) ?: return null
        val own = OwnData(spec.own, card.own, LocalDate.now(), System.currentTimeMillis())
        val problems = changes.mapNotNull { (key, op, value) -> own.apply(op, key, value)?.let { "$key：$it" } }
        dao.setOwn(id, own.json(), System.currentTimeMillis())
        refresh(context)
        return dao.byId(id)!! to problems
    }

    /** 桌面上点了卡片 [id] 上的 [action]（`own.add cups 1`、`reminder.done #7`） */
    suspend fun act(context: Context, id: Long, action: String) {
        val app = context.applicationContext
        val ref = Ref.parse(action) ?: return
        if (ref.source == CardSpecs.OWN) {
            val key = ref.args.firstOrNull() ?: return
            changeOwn(app, id, listOf(Triple(key, ref.name, ref.args.getOrNull(1))))
            return
        }
        val done = runCatching { CardHub.source(ref.source)?.act(app, ref.name, ref.args) == true }
            .onFailure { Log.e(TAG, "卡片上的动作没办成：$action", it) }.getOrDefault(false)
        // 模块办完多半自己喊过重画了；没喊的这里补一次
        if (done) refresh(app)
    }

    /** 对话里预览：照 [raw] 这份说明（那一轮交的那一版）画，数用卡片 [id] 现在的。按钮点不了 */
    suspend fun preview(context: Context, id: Long, raw: String): RemoteViews? {
        val spec = CardSpecs.read(raw) ?: return null
        val card = dao(context).byId(id)
        return CardViews.build(context, spec, card?.own, cardId = null)
    }

    /** 桌面上所有卡片重画一次。一张都没放就什么都不查 */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        CardSize.entries.forEach { size ->
            val ids = manager.getAppWidgetIds(ComponentName(app, providerOf(size)))
            if (ids.isEmpty()) return@forEach
            runCatching { CardRenderer.render(app, manager, size, ids) }
                .onFailure { Log.e(TAG, "重画卡片失败", it) }
        }
    }
}
