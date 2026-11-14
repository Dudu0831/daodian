package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.domain.Verdict
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
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
        "properties" to PLAN_PROPERTIES,
        "required" to PLAN_PROPERTIES.keys.toList(),
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
                    output = "已建好（id=$id）：${describe(p)}",
                    ok = true,
                    ref = id,
                    payload = Created(id, p)
                )
            }
            is Verdict.Rejected -> ToolOutcome(
                "没建。${verdict.reason} " +
                    if (verdict.selfFix) "这是参数填错了，改好再调一次，不用问用户。" else "照这个意思问用户一句，不要自己换个时间重试。",
                ok = false
            )
        }
    }

    companion object {
        const val NAME = "create_reminder"

        private val mapper = ObjectMapper()

        /** 回给模型的那句：「「喝水」，首次 …，重复 FREQ=DAILY。」建和改共用 */
        fun describe(p: ReminderPlan): String =
            "「${p.title}」，" +
                (if (p.allDay) "当天事项，日期 ${p.firstTriggerAt.take(10)}" else "首次 ${p.firstTriggerAt}") +
                "，" + (p.rrule?.let { "重复 $it" } ?: "不重复") +
                (if (p.gentle && !p.allDay) "，轻提醒（到点只发一条通知、不响铃）" else "") +
                (if (p.remindOn.isNotEmpty()) "，那天之前还提醒 ${p.remindOn.size} 次（${p.remindOn.joinToString("、")}）" else "") +
                (if (p.allDay && p.rrule == null) (if (p.carryOver) "，那天没点完成就每晚接着提醒" else "，那天提醒过就结束") else "") + "。"

        /** 一整条提醒的参数。`update_reminder` 前面多一个 id、其余照抄这一份 —— 改提醒就是把改完的整条重写一遍、重过闸门 */
        val PLAN_PROPERTIES: LinkedHashMap<String, Any?> = linkedMapOf(
            "title" to string("简短的动作，去掉「提醒我」这类壳。例如「喝水」「交房租」"),
            "firstTriggerAt" to string(
                "首次触发时刻，ISO-8601 带时区偏移，如 2026-09-03T19:40:00+08:00。allDay 为 true 时只看日期，钟点填 00:00:00；" +
                    "日期填事情本身的那一天（过期的那天、截止的那天、演出的那天），哪怕还有几个月 —— 不是第一次提醒的日子，提前提醒写在 remindOn 里"
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
            "allDay" to boolean("当天事项（只说了哪天、没说几点）填 true；说了具体钟点填 false"),
            "gentle" to boolean(
                "轻提醒：到点只发一条通知（响一声，不响铃、不弹全屏）填 true。只有用户明说了才填 true（「轻提醒」「不用响铃」「发个通知就行」），" +
                    "没提、拿不准一律 false"
            ),
            "remindOn" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "提前提醒：日子还远的当天事项，在那天之前还要哪几天提醒，一组 YYYY-MM-DD。他说「每个月提醒一下，最后一周每天提醒」，" +
                    "就把这些日子一天一天算出来列上（那天当晚本来就会提醒，不用列）。只有 allDay=true 且 rrule=null 能填；" +
                    "别的提醒、他没说要提前提醒的，填 []"
            ),
            "carryOver" to boolean(
                "到了那天没点完成，要不要每晚接着提醒，直到他点完成。要办的事（要交、要买、要处理掉）填 true；" +
                    "只是个日子（节日、生日、演出、纪念日）填 false，那天提醒过就结束。只有 allDay=true 且 rrule=null 看它，别的填 true"
            )
        )

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
                allDay = o.path("allDay").asBoolean(false),
                // 加这个参数之前的历史里没有它：当响铃
                gentle = o.path("gentle").asBoolean(false),
                // 加这两个参数之前的历史里没有：没有提前提醒、照旧顺延
                remindOn = o.path("remindOn").takeIf { it.isArray }?.mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty(),
                carryOver = o.path("carryOver").asBoolean(true)
            )
        }.getOrNull()

        private fun JsonNode.text(field: String): String? =
            get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

        private fun string(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun nullableString(desc: String) = mapOf("type" to listOf("string", "null"), "description" to desc)
        private fun boolean(desc: String) = mapOf("type" to "boolean", "description" to desc)
    }
}
