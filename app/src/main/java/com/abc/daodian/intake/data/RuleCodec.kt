package com.abc.daodian.intake.data

import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.AppIn
import com.abc.daodian.intake.core.rule.BodyHasAny
import com.abc.daodian.intake.core.rule.BodyHasNone
import com.abc.daodian.intake.core.rule.BodyStartsWith
import com.abc.daodian.intake.core.rule.Condition
import com.abc.daodian.intake.core.rule.TitleIs
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * 硬条件 ⇄ `rules.hard` 那一列的 JSON。一种条件一个 `type`，**存在手机上，不改名**：
 *
 * ```
 * {"type":"all","parts":[{"type":"app","pkgs":["com.android.mms"]},{"type":"body_any","words":["取件码","驿站"]}]}
 * ```
 *
 * 读不懂的（坏了、以后的版本写的新条件）整条读成 null，不跳过那一个条件 —— 少一个条件规则就变宽了
 */
object RuleCodec {

    private val mapper = ObjectMapper()

    fun encode(hard: AllOf): String = mapper.writeValueAsString(node(hard))

    fun decode(json: String): AllOf? = runCatching { mapper.readTree(json) }.getOrNull()?.let(::condition) as? AllOf

    private fun node(c: Condition): ObjectNode = mapper.createObjectNode().apply {
        when (c) {
            is AppIn -> put("type", "app").set<JsonNode>("pkgs", strings(c.pkgs))
            is TitleIs -> put("type", "title").put("text", c.text).put("exact", c.exact)
            is BodyStartsWith -> put("type", "starts_with").put("code", c.code)
            is BodyHasAny -> put("type", "body_any").set<JsonNode>("words", strings(c.words))
            is BodyHasNone -> put("type", "body_none").set<JsonNode>("words", strings(c.words))
            is AllOf -> put("type", "all").set<JsonNode>("parts", mapper.createArrayNode().apply { c.parts.forEach { add(node(it)) } })
        }
    }

    private fun strings(list: Collection<String>) = mapper.createArrayNode().apply { list.forEach { add(it) } }

    private fun condition(n: JsonNode): Condition? = when (n.get("type")?.asText()) {
        "app" -> n.texts("pkgs")?.let { AppIn(it.toCollection(LinkedHashSet())) }
        "title" -> n.text("text")?.let { t -> n.get("exact")?.takeIf { it.isBoolean }?.let { TitleIs(t, it.asBoolean()) } }
        "starts_with" -> n.text("code")?.let { BodyStartsWith(it) }
        "body_any" -> n.texts("words")?.let { BodyHasAny(it) }
        "body_none" -> n.texts("words")?.let { BodyHasNone(it) }
        "all" -> n.get("parts")?.takeIf { it.isArray }?.map { condition(it) ?: return null }?.let { AllOf(it) }
        else -> null
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()

    /** 每一项都得是字符串，有一项不是就算读不懂 */
    private fun JsonNode.texts(field: String): List<String>? =
        get(field)?.takeIf { it.isArray }?.map { if (it.isTextual) it.asText() else return null }
}
