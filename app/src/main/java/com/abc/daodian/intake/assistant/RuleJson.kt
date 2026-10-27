package com.abc.daodian.intake.assistant

import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.format.Formats
import com.abc.daodian.intake.core.inbox.fingerprint
import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.rule.RuleDraft
import com.abc.daodian.intake.core.rule.Verdict
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** 建规则那几个工具共用的：参数的 schema、读法，和拿一套硬条件对现在的通知预演 */
internal object RuleJson {

    val mapper = ObjectMapper()

    fun parse(arguments: String): JsonNode? = runCatching { mapper.readTree(arguments) }.getOrNull()?.takeIf { it.isObject }

    private fun strings(desc: String) = mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)

    /** `add_rule`、`preview_rule` 的硬条件部分，和 [RuleDraft] 一一对应 */
    val HARD: Array<Pair<String, Any?>> = arrayOf(
        "apps" to strings("听哪几个 app 的包名，至少一个，用 list_apps 查到的，不要猜"),
        "title" to mapOf(
            "type" to listOf("object", "null"),
            "description" to "按通知标题筛（聊天软件、短信的标题是发消息的人）。不按标题筛就 null",
            "properties" to linkedMapOf(
                "text" to mapOf("type" to "string", "description" to "标题，按人听时用 recent_senders 查到的，一字不差"),
                "exact" to mapOf("type" to "boolean", "description" to "true = 标题就是它；false = 标题里含它")
            ),
            "required" to listOf("text", "exact"),
            "additionalProperties" to false
        ),
        "body_any" to strings("正文含这几个词之一才进来（每个词至少 2 个字）。不按正文筛就空数组"),
        "body_none" to strings("正文含这几个词之一就不进来（比如短信加「验证码」）。没有就空数组"),
        "starts_with" to mapOf("type" to listOf("string", "null"), "description" to "暗号：正文以它开头才进来，不分大小写。他没说暗号就 null")
    )

    fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()

    private fun JsonNode.texts(field: String): List<String> = get(field)?.takeIf { it.isArray }?.mapNotNull { n -> n.takeIf { it.isTextual }?.asText() }.orEmpty()

    /** 读成草稿；name、soft 没有时（预演）填空串 */
    fun draftOf(o: JsonNode): RuleDraft {
        val title = o.get("title")?.takeIf { it.isObject }
        return RuleDraft(
            name = o.text("name").orEmpty(),
            apps = o.texts("apps"),
            title = title?.text("text"),
            titleExact = title?.get("exact")?.takeIf { it.isBoolean }?.asBoolean() ?: true,
            bodyAny = o.texts("body_any"),
            bodyNone = o.texts("body_none"),
            startsWith = o.text("starts_with"),
            soft = o.text("soft").orEmpty()
        )
    }

    /**
     * 拿 [hard] 对一遍 [samples]（同一条只算一次），说现在对得上几条、各是哪个 app 的、标题举几个。
     * 遮蔽了正文、判不了的也算对得上 —— 真来了会先存下等真正文
     */
    fun preview(hard: AllOf, samples: List<Notice>, names: AppNames): String {
        val hits = samples.distinctBy { it.fingerprint }.filter { hard.test(Formats.of(it.pkg).normalize(it)) != Verdict.NO }
        if (hits.isEmpty()) return "现在通知栏和收件箱里对得上 0 条"
        val byApp = hits.groupBy { it.pkg }.entries.joinToString("；") { (pkg, list) ->
            val titles = list.mapNotNull { Formats.of(pkg).normalize(it).title }.distinct().take(3)
            "${names.of(pkg)} ${list.size} 条" + if (titles.isEmpty()) "" else "（${titles.joinToString("、")}）"
        }
        return "现在通知栏和收件箱里对得上 ${hits.size} 条：$byApp"
    }
}
