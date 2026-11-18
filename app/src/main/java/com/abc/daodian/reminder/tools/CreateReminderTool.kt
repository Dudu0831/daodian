package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.domain.Checklist
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.reminder.domain.ReminderText
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
 * 落库不在这里写死：工具不碰 Context，由接线的一方传入（助手是 `Reminders.commitPlan`，提醒子代理另写来源）。
 *
 * 参数就是一条提醒的六件事（DESIGN.md §4.6），[withItems] 时多一份清单（§4.5），成功时 [ToolOutcome.payload] 是 [Created]。
 */
class CreateReminderTool(
    private val commit: suspend (ReminderSpec, List<String>, ToolContext) -> Long,
    private val withItems: Boolean = true
) : Tool {

    data class Created(val reminderId: Long, val spec: ReminderSpec, val items: List<String> = emptyList())

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "在用户手机上建一条提醒，会真的排闹钟。一件事建一条：几个钟点、提前几天、没做完接着提，都是这一条上的设置，不要拆成几条。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to if (withItems) LinkedHashMap(PLAN_PROPERTIES).apply { put("items", ITEMS_PROPERTY) } else PLAN_PROPERTIES,
        "required" to PLAN_PROPERTIES.keys.toList() + listOfNotNull("items".takeIf { withItems }),
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val plan = planOf(arguments)
            ?: return ToolOutcome("没建。参数不是合法的 JSON 或缺字段：${arguments.take(200)}", ok = false)

        val items = if (withItems) itemsOf(arguments) else emptyList()
        Checklist.problemOf(items)?.let { return ToolOutcome("没建。$it 这是参数填错了，改好再调一次，不用问用户。", ok = false) }

        return when (val verdict = PlanValidator.validate(plan, context.now)) {
            is Verdict.Ok -> {
                val id = commit(verdict.spec, items, context)
                ToolOutcome(
                    output = "已建好（id=$id）：${ReminderText.describe(verdict.spec, context.now.toLocalDate())}" +
                        if (items.isEmpty()) "" else "；清单 ${items.size} 样：${items.joinToString("、")}",
                    ok = true,
                    ref = id,
                    payload = Created(id, verdict.spec, items)
                )
            }
            is Verdict.Rejected -> rejected("没建。", verdict)
        }
    }

    companion object {
        const val NAME = "create_reminder"

        private val mapper = ObjectMapper()

        /** 闸门拦下时回给模型的那句。建和改共用 */
        fun rejected(prefix: String, verdict: Verdict.Rejected) = ToolOutcome(
            prefix + verdict.reason + " " +
                if (verdict.selfFix) "这是参数填错了，改好再调一次，不用问用户。" else "照这个意思问用户一句，不要自己换个时间重试。",
            ok = false
        )

        /**
         * 一条提醒的参数（§4.6 的六件事）。这里只写每一项是什么、怎么写；什么时候填什么（默认响不响、催不催、要不要提前）
         * 是判断，写在用它的 agent 的提示词里，只写一份（助手 `AssistantPrompt`「提醒的规则」、提醒子代理 `ReminderNoticePrompt`）。
         * 跟不跟时区走（`wallClockAnchored`）不让模型填，代码照重复、钟点推（[anchoredOf]）。
         *
         * [patch] = `update_reminder` 那一份：每一项都可以填 null，表示不改。
         */
        fun planProperties(patch: Boolean): LinkedHashMap<String, Any?> {
            fun keep(desc: String) = if (patch) "$desc。不改填 null" else desc
            fun str(desc: String) = mapOf("type" to (if (patch) listOf("string", "null") else "string"), "description" to keep(desc))
            fun arr(item: String, desc: String) = mapOf("type" to (if (patch) listOf("array", "null") else "array"), "items" to mapOf("type" to item), "description" to keep(desc))
            fun enumOf(values: List<String>, desc: String) =
                mapOf("type" to (if (patch) listOf("string", "null") else "string"), "enum" to (if (patch) values + listOf(null) else values), "description" to keep(desc))
            fun clearable(desc: String, clear: String) =
                mapOf("type" to listOf("string", "null"), "description" to if (patch) "$desc。不改填 null，${clear}填 \"\"" else "$desc；没有填 null")
            return linkedMapOf(
                "title" to str("简短的动作，去掉「提醒我」这类壳：「喝水」「交房租」"),
                "day" to str("事情本身那天，YYYY-MM-DD；重复的填头一次"),
                "times" to arr("string", "几点，HH:mm，一天几次列几个，最多 ${PlanValidator.MAX_TIMES} 个；[] = 不定钟点，那天晚上收尾时刻提醒"),
                "basis" to mapOf("type" to "string", "description" to "你的推算依据，如「用户时刻 09-18 周五 + 1 天，15:00」。必须填，出错时靠它看你哪步算歪了"),
                "note" to clearable("他自己给的补充（地点、要带的东西），会原样出现在通知上，不写你的解释", "要去掉备注"),
                "rrule" to clearable(
                    "重复规则，RFC 5545 子集：FREQ=DAILY|WEEKLY|MONTHLY|YEARLY、INTERVAL、BYDAY（仅 WEEKLY）、BYMONTHDAY（仅 MONTHLY）、COUNT、UNTIL",
                    "改成不重复"
                ),
                "remindBefore" to arr("integer", "提前几天在那天晚上提一下，如 [30, 7, 1]；[] = 不提前"),
                "ring" to enumOf(listOf("alarm", "notice"), "alarm 响铃（全屏，一直响到点掉）；notice 只发一条通知（轻提醒）"),
                "nag" to enumOf(listOf("none", "daily"), "响过没点完成：none 不催；daily 每天晚上收尾时刻再提，直到点完成")
            )
        }

        val PLAN_PROPERTIES: LinkedHashMap<String, Any?> = planProperties(patch = false)

        /**
         * 跟不跟时区走（§7.1）：重复的、不定钟点的跟着人走（当地这个钟点）；一次性定了钟点的固定那一瞬间（开会、航班）。
         * 11-17 起不让模型填：规矩是死的，它填只会填错
         */
        fun anchoredOf(rrule: String?, times: List<String>): Boolean = rrule != null || times.isEmpty()

        /** 清单（§4.5）：只给建提醒，改清单另有 `change_checklist` —— 整条重写会把勾冲掉 */
        val ITEMS_PROPERTY = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "string"),
            "description" to "清单：这件事底下要带、要买、要准备的几样，一样一项，每项最多 ${Checklist.MAX_CHARS} 个字，最多 ${Checklist.MAX_ITEMS} 样；没有填 []"
        )

        /** 清单那一项；没填是空的 */
        fun itemsOf(arguments: String): List<String> = runCatching {
            mapper.readTree(arguments).path("items").takeIf { it.isArray }
                ?.mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        }.getOrDefault(emptyList())

        /** 参数 JSON → [ReminderPlan]，缺必填字段或不是 JSON 就是 null */
        fun planOf(arguments: String): ReminderPlan? = runCatching {
            val o = mapper.readTree(arguments)
            val times = timesOf(o) ?: emptyList()
            val rrule = o.text("rrule")
            ReminderPlan(
                title = o.text("title") ?: return null,
                day = o.text("day") ?: return null,
                times = times,
                basis = o.text("basis").orEmpty(),
                note = o.text("note"),
                rrule = rrule,
                remindBefore = remindBeforeOf(o) ?: emptyList(),
                ring = o.text("ring") ?: return null,
                nag = o.text("nag") ?: return null,
                wallClockAnchored = anchoredOf(rrule, times)
            )
        }.getOrNull()

        /** times 那一项；没给（或 null）是 null */
        internal fun timesOf(o: JsonNode): List<String>? =
            o.get("times")?.takeIf { it.isArray }?.mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }

        /** remindBefore 那一项；没给（或 null）是 null */
        internal fun remindBeforeOf(o: JsonNode): List<Int>? =
            o.get("remindBefore")?.takeIf { it.isArray }?.mapNotNull { n -> n.takeIf { it.canConvertToInt() }?.asInt() }

        /** 推算依据，编辑页「依据」那一段。11-16 以前的旧参数也有这个字段，单读它不用整条认得 */
        fun basisOf(arguments: String): String? = runCatching { mapper.readTree(arguments).text("basis") }.getOrNull()

        internal fun JsonNode.text(field: String): String? =
            get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    }
}
