package com.abc.daodian.reminder.tools

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.domain.Verdict
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 建一条提醒。过 [PlanValidator] 闸门，过了才交给 [commit] 落库排期；
 * 闸门拦下的原因原样回给模型，由它去问用户。
 *
 * 落库不在这里写死：harness 不依赖 ui/，由接线的一方传入（到时候就是 `PlanCommitter.commit`）。
 *
 * 成功时 [ToolOutcome.payload] 是 [Created]。
 */
class CreateReminderTool(private val commit: suspend (ReminderPlan, ToolContext) -> Long) : Tool {

    data class Created(val reminderId: Long, val plan: ReminderPlan)

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "在用户手机上建一条提醒，会真的排一个闹钟。只有他明确要被提醒、且能确定是哪一天时才调用。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "title" to string("简短的动作，去掉「提醒我」这类壳。例如「喝水」「交房租」"),
            "firstTriggerAt" to string(
                "首次触发时刻，ISO-8601 带时区偏移，如 2026-09-03T19:40:00+08:00。allDay 为 true 时只看日期，钟点填 00:00:00"
            ),
            "basis" to string("你的推算依据，如「用户时刻 09-18 周五 + 1 天，15:00」。必须填，出错时靠它看你哪步算歪了"),
            "note" to nullableString(
                "用户自己给的补充（地点、要带的东西），他没说就 null。不要写你对这条提醒的解释 —— 会原样出现在他的通知上"
            ),
            "rrule" to nullableString(
                "重复规则，RFC 5545 子集，不重复就 null。只允许 FREQ=DAILY|WEEKLY|MONTHLY|YEARLY、INTERVAL、" +
                    "BYDAY（仅 WEEKLY）、BYMONTHDAY（仅 MONTHLY）、COUNT、UNTIL"
            ),
            "wallClockAnchored" to boolean("重复类（每天早上 8 点）填 true，跟着用户所在时区走；一次性的具体约会填 false"),
            "allDay" to boolean("当天事项（只说了哪天、没说几点）填 true；说了具体钟点填 false")
        ),
        "required" to listOf("title", "firstTriggerAt", "basis", "note", "rrule", "wallClockAnchored", "allDay"),
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val plan = planOf(arguments)
            ?: return ToolOutcome("没建。参数不是合法的 JSON 或缺字段：${arguments.take(200)}", ok = false)

        return when (val verdict = PlanValidator.validate(plan, context.now)) {
            is Verdict.Ok -> {
                val id = commit(verdict.plan, context)
                val p = verdict.plan
                ToolOutcome(
                    output = "已建好（id=$id）：「${p.title}」，" +
                        (if (p.allDay) "当天事项，日期 ${p.firstTriggerAt.take(10)}" else "首次 ${p.firstTriggerAt}") +
                        "，" + (p.rrule?.let { "重复 $it" } ?: "不重复") + "。",
                    ok = true,
                    ref = id,
                    payload = Created(id, p)
                )
            }
            is Verdict.Rejected ->
                ToolOutcome("没建。${verdict.reason} 照这个意思问用户一句，不要自己换个时间重试。", ok = false)
        }
    }

    companion object {
        const val NAME = "create_reminder"

        private val mapper = ObjectMapper()

        /** 参数 JSON → [ReminderPlan]，缺必填字段或不是 JSON 就是 null。重建历史卡片也用它 */
        fun planOf(arguments: String): ReminderPlan? = runCatching {
            val o = mapper.readTree(arguments)
            ReminderPlan(
                title = o.text("title") ?: return null,
                firstTriggerAt = o.text("firstTriggerAt") ?: return null,
                basis = o.text("basis").orEmpty(),
                note = o.text("note"),
                rrule = o.text("rrule"),
                wallClockAnchored = o.path("wallClockAnchored").asBoolean(true),
                allDay = o.path("allDay").asBoolean(false)
            )
        }.getOrNull()

        private fun JsonNode.text(field: String): String? =
            get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

        private fun string(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun nullableString(desc: String) = mapOf("type" to listOf("string", "null"), "description" to desc)
        private fun boolean(desc: String) = mapOf("type" to "boolean", "description" to desc)
    }
}
