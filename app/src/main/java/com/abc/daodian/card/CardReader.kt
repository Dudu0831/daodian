package com.abc.daodian.card

import android.content.Context
import com.abc.daodian.card.own.OwnData
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref
import kotlinx.coroutines.CancellationException

/**
 * 画一张卡片时取数：own 的问卡片自己（[own]），别的问交它的模块。同一个名字一次重画里只问一次。
 * 模块那边出了错当没有 —— 卡片上那一块空着，不连累整张卡（同 `AssistantPlugins.backgrounds`）
 */
class CardReader(private val context: Context, private val sources: List<CardSource>, val own: OwnData) {

    private val memo = HashMap<String, CardValue?>()

    suspend fun value(ref: Ref?): CardValue? {
        ref ?: return null
        val key = ref.toString()
        if (memo.containsKey(key)) return memo[key]
        val v = if (ref.source == CardSpecs.OWN) own.read(ref.name) else try {
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

    suspend fun flag(ref: Ref?): Boolean = (value(ref) as? CardValue.Flag)?.on == true

    /** 一个绑定或一个字面数 */
    suspend fun number(text: String): Long? =
        Ref.whole(text)?.let { (value(it) as? CardValue.Number)?.value } ?: text.trim().toLongOrNull()

    companion object {
        const val MISSING = "—"
    }
}
