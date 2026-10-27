package com.abc.daodian.intake.steward

import com.abc.daodian.intake.core.steward.Decision
import com.abc.daodian.intake.core.steward.DecisionBuffer
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 管家的两个工具（DESIGN.md §12.4）：只往 [DecisionBuffer] 里放决定（Command），不改库；
 * 一轮跑完由 [Steward] 一个事务落。每次办完把还没说法的念给模型，免得漏
 */
internal abstract class StewardTool(private val buffer: DecisionBuffer) : Tool {

    override val effect = ToolEffect.WRITE

    protected abstract fun decisionOf(o: JsonNode, notice: Long, rules: Set<Long>): Decision?

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = runCatching { MAPPER.readTree(arguments) }.getOrNull()?.takeIf { it.isObject }
            ?: return ToolOutcome("没记下：参数不是合法的 JSON。", ok = false)
        val notice = o.get("notice")?.takeIf { it.canConvertToLong() }?.asLong()
            ?: return ToolOutcome("没记下：notice 要填 n 编号的数字（n41 填 41）。", ok = false)
        val rules = o.get("rules")?.takeIf { it.isArray }?.mapNotNull { r -> r.takeIf { it.canConvertToLong() }?.asLong() }.orEmpty().toSet()
        val d = decisionOf(o, notice, rules) ?: return ToolOutcome("没记下：参数缺了，照 schema 填全。", ok = false)
        buffer.offer(d)?.let { return ToolOutcome("没记下：$it。", ok = false) }
        return ToolOutcome("记下了。${left()}", ok = true)
    }

    private fun left(): String {
        val left = buffer.remaining()
        if (left.isEmpty()) return "这一批每条都有说法了。"
        return "还没说法的：" + left.entries.joinToString("；") { (n, rules) -> "n$n 的 " + rules.sorted().joinToString("、") { "r$it" } } + "。"
    }

    companion object {
        val MAPPER = ObjectMapper()

        val NOTICE = mapOf("type" to "integer", "description" to "通知的 n 编号的数字：n41 填 41")
        val RULES = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "integer"),
            "description" to "这个决定对的是这条通知中的哪几条规则（r 编号的数字）。空数组 = 它还没说法的全部"
        )
    }
}

/** 交给一个去处。一条可以交给几个去处，各调一次 */
internal class HandOffTool(buffer: DecisionBuffer, destinations: List<String>) : StewardTool(buffer) {

    override val name = NAME

    override val description = "把一条通知交给一个去处（模块），附一句话告诉它要办什么。附言写到它不用看规则就能办。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "notice" to NOTICE,
            "rules" to RULES,
            "to" to mapOf("type" to "string", "enum" to destinations, "description" to "去处的 id"),
            "note" to mapOf("type" to "string", "description" to "附言：写清要它办什么（几点、标题、要不要轻提醒）。记账那种不用交代的可以空着")
        ),
        "required" to listOf("notice", "rules", "to", "note"),
        "additionalProperties" to false
    )

    override fun decisionOf(o: JsonNode, notice: Long, rules: Set<Long>): Decision? {
        val to = o.get("to")?.takeIf { it.isTextual }?.asText()?.trim() ?: return null
        val note = o.get("note")?.takeIf { it.isTextual }?.asText().orEmpty()
        return Decision.HandOff(notice, rules, to, note)
    }

    companion object {
        const val NAME = "hand_off"
    }
}

/** 不相关：照规则不该办的（广告、验证码、闲聊……） */
internal class IgnoreTool(buffer: DecisionBuffer) : StewardTool(buffer) {

    override val name = NAME

    override val description = "这条通知照这几条规则不用办。理由写一句，记录页上给人看。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "notice" to NOTICE,
            "rules" to RULES,
            "why" to mapOf("type" to "string", "description" to "为什么不相关，一句：「广告」「验证码」「她说她自己的安排」")
        ),
        "required" to listOf("notice", "rules", "why"),
        "additionalProperties" to false
    )

    override fun decisionOf(o: JsonNode, notice: Long, rules: Set<Long>): Decision? {
        val why = o.get("why")?.takeIf { it.isTextual }?.asText() ?: return null
        return Decision.Ignore(notice, rules, why)
    }

    companion object {
        const val NAME = "ignore"
    }
}
