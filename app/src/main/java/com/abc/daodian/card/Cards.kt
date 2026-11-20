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
import com.abc.daodian.card.render.CardRenderer
import com.abc.daodian.card.render.CardViews
import com.abc.daodian.card.render.Space
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref
import kotlinx.coroutines.sync.withLock

/**
 * 桌面卡片的进出口：画一张、改一张、请桌面放上去、桌面上点了什么、数据变了重画。见 DESIGN.md §8.5
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
    suspend fun create(context: Context, spec: CardSpec): Card {
        val now = System.currentTimeMillis()
        val draft = Card(name = spec.name, size = spec.size.key, spec = CardSpecs.write(spec), createdAt = now, updatedAt = now)
        return draft.copy(id = dao(context).insert(draft))
    }

    /** 换掉一张的说明，在桌面上的跟着重画。没有这张是 null */
    suspend fun redraw(context: Context, id: Long, spec: CardSpec): Card? {
        val dao = dao(context)
        dao.byId(id) ?: return null
        dao.redraw(id, spec.name, spec.size.key, CardSpecs.write(spec), System.currentTimeMillis())
        refresh(context)
        return dao.byId(id)
    }

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

    /** 桌面上点了 [action]（`reminder.done #7`、清单一行的 `reminder.check #12 3`）：交给那个模块办。[on] 见 `CardSource.act` */
    suspend fun act(context: Context, action: String, on: Boolean? = null) {
        val app = context.applicationContext
        val ref = Ref.parse(action) ?: return
        val done = runCatching { CardHub.source(ref.source)?.act(app, ref.name, ref.args, on) == true }
            .onFailure { Log.e(TAG, "卡片上的动作没办成：$action", it) }.getOrDefault(false)
        // 模块办完多半自己喊过重画了；没喊的这里补一次
        if (done) refresh(app)
    }

    /** 对话里预览：照 [spec] 这份说明（那一轮交的那一版）、按它那种大小的标准尺寸画，数是现在的。点不了 */
    suspend fun preview(context: Context, spec: CardSpec): RemoteViews {
        val space = Space(spec.size.widthDp.toFloat(), spec.size.heightDp.toFloat())
        return CardViews.build(context, spec, cardId = null, spaces = listOf(space)).views.getValue(space)
    }

    /**
     * 存之前先照标准尺寸截一次（只截系统此刻那种颜色）：画没画出来、裁没裁边、脚本报没报错，有就退回给模型改（§8.5）。
     * [filled] 是这一刻绑上的值（「reminder.days_left #1 = 35」），让它对一眼绑对了没有
     */
    class Trial(val problems: List<String>, val filled: List<String>, val text: String)

    suspend fun trial(context: Context, spec: CardSpec): Trial {
        val app = context.applicationContext
        val space = Space(spec.size.widthDp.toFloat(), spec.size.heightDp.toFloat())
        val built = CardViews.build(app, spec, cardId = null, spaces = listOf(space), both = false)
        val reader = CardReader(app, CardHub.sources)
        val filled = CardSpecs.refs(spec.html).map { ref ->
            val v = reader.value(ref)
            "$ref = " + when (v) {
                null -> "取不到"
                is com.abc.daodian.card.plugin.CardValue.Rows -> "${v.total} 行：" + v.rows.take(4).joinToString("、") { it.title } + if (v.total > 4) "…" else ""
                else -> v.asText().take(60)
            }
        }
        return Trial(built.problems, filled, built.text)
    }

    private val refreshing = kotlinx.coroutines.sync.Mutex()
    private val asked = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var served = 0L

    /**
     * 数据变了：桌面上的卡片照现在的数重画，和上次交的一样的那几块跳过（`CardRenderer`）。一张都没放就什么都不查。
     * 一起喊来的几次并成一遍：点一个勾，模块办完喊一次、[act] 再补一次，提醒那边改清单可能还喊一次 ——
     * 等锁的时候已经有一遍在自己喊过之后开始画了，就不再画。返回时画的一定是喊的那一刻之后的数
     */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val mine = asked.incrementAndGet()
        refreshing.withLock {
            if (served >= mine) return
            served = asked.get()
            val manager = AppWidgetManager.getInstance(app)
            CardSize.entries.forEach { size ->
                val ids = manager.getAppWidgetIds(ComponentName(app, providerOf(size)))
                if (ids.isEmpty()) return@forEach
                runCatching { CardRenderer.render(app, manager, size, ids, onlyChanged = true) }
                    .onFailure { Log.e(TAG, "重画卡片失败", it) }
            }
        }
    }
}
