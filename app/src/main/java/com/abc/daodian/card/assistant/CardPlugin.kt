package com.abc.daodian.card.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceText
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.card.CardHub
import com.abc.daodian.card.CardModule
import com.abc.daodian.card.Cards
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.runtime.kit.Kit
import com.abc.daodian.runtime.tool.Tool

/**
 * 桌面卡片给助手的：一个平时收着的工具包（画、钉、看、改数四个工具连同用法，[CardTools.kit]）、它们的痕、
 * 画好那一步在正文里挂的预览（[CardPreview]）。没有常驻的工具（DESIGN.md §6.11、§8.5）
 */
object CardPlugin : AssistantPlugin {

    override val id = CardModule.ID

    override fun tools(context: Context): List<Tool> = emptyList()

    override fun kits(context: Context): List<Kit> {
        val app = context.applicationContext
        return listOf(CardTools.kit(object : CardDesk {
            override val vocabulary get() = CardHub.vocabulary
            override suspend fun cards() = Cards.all(app)
            override suspend fun create(raw: String, spec: CardSpec) = Cards.create(app, raw, spec)
            override suspend fun redraw(id: Long, raw: String, spec: CardSpec) = Cards.redraw(app, id, raw, spec)
            override suspend fun pin(id: Long) = Cards.pin(app, id)
            override suspend fun changeOwn(id: Long, changes: List<Triple<String, String, String?>>): Pair<Card, List<String>>? =
                Cards.changeOwn(app, id, changes)
        }))
    }

    /** 「✓ 卡片 · 喝水」。名字取结果第一行「」里那段 */
    override fun trace(call: ToolTrace): TraceView? {
        val (working, settled, prefix) = when (call.tool) {
            CardTools.DESIGN -> Triple("在画卡片", "卡片", "没画。")
            CardTools.PIN -> Triple("在放桌面", "桌面卡片", "没钉。")
            CardTools.CHANGE -> Triple("在改卡片", "卡片", "没改。")
            else -> return null
        }
        val name = call.output?.lineSequence()?.firstOrNull()?.substringAfter('「', "")?.substringBefore('」').orEmpty()
        return TraceView(
            working = working,
            settled = if (call.failed) "卡片没办成" else settled,
            text = when {
                call.failed -> TraceText.reasonOf(call.output, prefix)
                call.tool == CardTools.CHANGE -> listOf(name, "改了数").filter { it.isNotEmpty() }.joinToString(" · ")
                else -> name
            }
        )
    }

    /** 画好的那一步，正文里挂一张预览：照那一轮交的说明画（不是最新的那版），数用现在的 */
    override fun hasTraceBody(call: ToolTrace): Boolean = call.tool == CardTools.DESIGN && call.ok && call.ref != null

    @Composable
    override fun TraceBody(call: ToolTrace) {
        val raw = CardTools.parse(call.arguments)?.get("spec")?.asText() ?: return
        CardPreview(cardId = call.ref ?: return, raw = raw)
    }
}
