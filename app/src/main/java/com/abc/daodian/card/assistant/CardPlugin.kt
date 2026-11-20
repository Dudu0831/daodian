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
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.runtime.kit.Kit
import com.abc.daodian.runtime.tool.Tool

/**
 * 桌面卡片给助手的：一个平时收着的工具包（画、看两个工具连同用法，[CardTools.kit]；放上桌面由他点预览底下那行）、它们的痕、
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
            override suspend fun trial(spec: CardSpec) = Cards.trial(app, spec).let { CardTrial(it.problems, it.filled, it.text) }
            override suspend fun create(spec: CardSpec) = Cards.create(app, spec)
            override suspend fun redraw(id: Long, spec: CardSpec) = Cards.redraw(app, id, spec)
        }))
    }

    /** 「✓ 卡片 · 去旧金山」。名字取结果第一行「」里那段 */
    override fun trace(call: ToolTrace): TraceView? {
        val (working, settled, prefix) = when (call.tool) {
            CardTools.DESIGN -> Triple("在画卡片", "卡片", "没画。")
            "pin_card" -> Triple("在放桌面", "桌面卡片", "没钉。") // 11-19 删了，旧对话里的痕照旧画
            else -> return null
        }
        val name = call.output?.lineSequence()?.firstOrNull()?.substringAfter('「', "")?.substringBefore('」').orEmpty()
        return TraceView(
            working = working,
            settled = if (call.failed) "卡片没办成" else settled,
            text = if (call.failed) TraceText.reasonOf(call.output, prefix) else name
        )
    }

    /** 画好的那一步，正文里挂一张预览：照那一轮交的说明画（不是最新的那版），数是现在的 */
    override fun hasTraceBody(call: ToolTrace): Boolean = call.tool == CardTools.DESIGN && call.ok && call.ref != null && specOf(call) != null

    /** 一轮里同一张卡片（同一个编号，或同一个名字：模型改大小时常新建一张）画了几版，只留最后那版的预览 */
    override fun traceBodyKey(call: ToolTrace): String? = specOf(call)?.name?.takeIf { it.isNotBlank() }?.let { "card:$it" }
        ?: call.ref?.let { "card#$it" }

    @Composable
    override fun TraceBody(call: ToolTrace) {
        val spec = specOf(call) ?: return
        CardPreview(cardId = call.ref ?: return, spec = spec)
    }

    /** 那一轮交的网页。11-19 以前零件拼的那版（参数里是 spec）读不出，不挂预览 */
    private fun specOf(call: ToolTrace): CardSpec? {
        val o = CardTools.parse(call.arguments) ?: return null
        val html = o.get("html")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val size = CardSize.of(o.get("size")?.asText()) ?: return null
        return CardSpec(o.get("name")?.asText().orEmpty(), size, html)
    }
}
