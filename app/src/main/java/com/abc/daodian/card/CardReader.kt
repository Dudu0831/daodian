package com.abc.daodian.card

import android.content.Context
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.spec.Ref
import kotlinx.coroutines.CancellationException

/**
 * 画一张卡片时取数：问交它的模块。同一个名字一次重画里只问一次。
 * 模块那边出了错当没有 —— 卡片上那一块空着，不连累整张卡（同 `AssistantPlugins.backgrounds`）
 */
class CardReader(private val context: Context, private val sources: List<CardSource>) {

    private val memo = HashMap<String, CardValue?>()

    suspend fun value(ref: Ref?): CardValue? {
        ref ?: return null
        val key = ref.toString()
        if (memo.containsKey(key)) return memo[key]
        val v = try {
            sources.firstOrNull { it.id == ref.source }?.read(context, ref.name, ref.args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        memo[key] = v
        return v
    }

    /** 字里的绑定换成值；取不到的写「—」 */
    suspend fun text(template: String): String = Ref.fill(template) { value(it)?.asText() ?: MISSING }

    /**
     * 点 [action] 之前那一样是不是勾着的：在这次取过的几组行里找 tap 是它的那一行；不是行里的（「完成」按钮）看那条办完了没。
     * 桌面当场画勾时要知道是往勾上切还是往没勾切（`render/CardViews`）
     */
    suspend fun on(action: String): Boolean {
        memo.values.forEach { v -> (v as? CardValue.Rows)?.rows?.firstOrNull { it.action == action }?.let { return it.done } }
        val a = Ref.parse(action) ?: return false
        return a.name == "done" && flag(Ref(a.source, "done", a.args))
    }

    suspend fun flag(ref: Ref?): Boolean = (value(ref) as? CardValue.Flag)?.on == true

    /** 整段是一个绑定的数 */
    suspend fun number(text: String): Long? = Ref.whole(text)?.let { (value(it) as? CardValue.Number)?.value }

    /**
     * 卡片绑的那几条里，有没有哪一条没了（提醒删了）：有就是那个来源的名字（「提醒」），整张卡画成一行灰字。
     * 出了错当没删 —— 宁可画一块空的，不把好好的卡片说成没了
     */
    suspend fun gone(refs: List<Ref>): String? {
        refs.distinctBy { it.toString() }.forEach { ref ->
            val source = sources.firstOrNull { it.id == ref.source } ?: return@forEach
            val gone = try {
                source.gone(context, ref.name, ref.args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (gone) return source.label
        }
        return null
    }

    companion object {
        const val MISSING = "—"
    }
}
