package com.abc.daodian.card.assistant

import android.content.Context
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.PartialArgs
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceText
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.card.CardHub
import com.abc.daodian.card.CardModule
import com.abc.daodian.card.CardTemplate
import com.abc.daodian.card.Cards
import com.abc.daodian.runtime.kit.Kit
import com.abc.daodian.runtime.tool.Tool
import java.time.LocalDate

/**
 * 桌面卡片给助手的：一个平时收着的工具包（钉、看、换三个工具连同用法，[CardTools.kit]）和它们的痕。
 * 钉卡片是偶尔才做的事，没有常驻的工具（DESIGN.md §6.11、§8.5）
 */
object CardPlugin : AssistantPlugin {

    override val id = CardModule.ID

    override fun tools(context: Context): List<Tool> = emptyList()

    override fun kits(context: Context): List<Kit> {
        val app = context.applicationContext
        return listOf(CardTools.kit(object : CardDesk {
            override val sources = CardHub.sources.map { CardDesk.Source(it.id, it.label, it.brief) }
            override suspend fun subject(source: String, ref: Long) = CardHub.source(source)?.subject(app, ref)
            override suspend fun cards() = Cards.all(app)
            override suspend fun pin(source: String, ref: Long, template: CardTemplate, start: LocalDate?) =
                Cards.pin(app, source, ref, template, start).let { CardDesk.Placing(it.card, it.asked) }
            override suspend fun retarget(cardId: Long, source: String, ref: Long, start: LocalDate?) =
                Cards.retarget(app, cardId, source, ref, start)
        }))
    }

    /** 「✓ 桌面卡片 大数字 · 牛奶过期 ›」，点了去钉的那件事。字取结果第一行冒号后面那段 */
    override fun trace(call: ToolTrace): TraceView? {
        val (working, prefix) = when (call.tool) {
            CardTools.PIN -> "在备卡片" to "没钉。"
            CardTools.CHANGE -> "在换卡片" to "没换。"
            else -> return null
        }
        return TraceView(
            working = working,
            settled = if (call.failed) "卡片没办成" else "桌面卡片",
            text = if (call.failed) TraceText.reasonOf(call.output, prefix)
            else call.output?.lineSequence()?.firstOrNull()?.substringAfter('：', "").orEmpty(),
            route = call.ref?.takeIf { call.ok }?.let { ref ->
                PartialArgs.text(call.arguments, "source")?.let { CardHub.source(it) }?.route(ref)
            }
        )
    }
}
