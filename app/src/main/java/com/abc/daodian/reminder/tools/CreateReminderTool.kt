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
        "在用户手机上建一条提醒，会真的排闹钟。只有他明确要被提醒、且能确定是哪一天时才调用。" +
            "一件事就建一条：一天几个钟点、提前几天提醒、没做完接着提，都是这一条上的设置，不要拆成几条。"

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

        /** 一整条提醒的参数。`update_reminder` 前面多一个 id、其余照抄这一份 —— 改提醒就是把改完的整条重写一遍、重过闸门 */
        val PLAN_PROPERTIES: LinkedHashMap<String, Any?> = linkedMapOf(
            "title" to string("简短的动作，去掉「提醒我」这类壳。例如「喝水」「交房租」"),
            "day" to string(
                "事情本身那天，YYYY-MM-DD：过期的那天、截止的那天、演出的那天，哪怕还有几个月 —— 不是第一次提醒的日子。" +
                    "重复的填头一次（今天或以后）。他连哪天都没说就填今天"
            ),
            "times" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "几点，HH:mm，一天几次就列几个（最多 ${PlanValidator.MAX_TIMES} 个）：「9 点到 21 点每两小时」展开成 09:00、11:00 … 21:00。" +
                    "他没说几点就填 []：那天晚上收尾时刻统一提醒，不要追问几点"
            ),
            "basis" to string("你的推算依据，如「用户时刻 09-18 周五 + 1 天，15:00」。必须填，出错时靠它看你哪步算歪了"),
            "note" to nullableString(
                "用户自己给的补充（地点、要带的东西），他没说就 null。不要写你对这条提醒的解释 —— 会原样出现在他的通知上"
            ),
            "rrule" to nullableString(
                "重复规则，RFC 5545 子集，不重复就 null。只允许 FREQ=DAILY|WEEKLY|MONTHLY|YEARLY、INTERVAL、" +
                    "BYDAY（仅 WEEKLY）、BYMONTHDAY（仅 MONTHLY）、COUNT、UNTIL。和几点无关：没说几点的也能重复"
            ),
            "remindBefore" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "integer"),
                "description" to "那天之前还要提前几天提一下，如 [30, 7, 1]；提前的那几次都在那天晚上收尾时刻发一条通知。" +
                    "重复的也能填，每一次都照这几天往前算，但要比两次之间的间隔短。他没说要提前提醒就填 []，不要替他加"
            ),
            "ring" to enum(
                listOf("alarm", "notice"),
                "到点怎么响：alarm 响铃（全屏、一直响到点掉），notice 只发一条通知（轻提醒）。" +
                    "有钟点的默认 alarm，他明说「轻提醒」「不用响铃」「发个通知就行」才填 notice。" +
                    "没钟点的一律 notice：它们都在那天晚上收尾时刻并成一条通知。他要响铃又没说几点，问他几点"
            ),
            "nag" to enum(
                listOf("none", "soon", "daily"),
                "响过他没点完成怎么办：none 不催，响过就算；soon 过 30 分钟再提，最多 3 次；daily 每天晚上收尾时刻再提，直到他点完成。" +
                    "没钟点的一次性要办的事（要交、要买、要处理掉）默认 daily；只是个日子（节日、生日、演出）填 none；" +
                    "有钟点的、重复的默认 none，他说「没做就一直提醒我」「催我」才填 soon 或 daily。每天都有的不能填 daily"
            ),
            "wallClockAnchored" to boolean(
                "有钟点的才看：重复类（每天早上 8 点）填 true，跟着用户所在时区走；一次性的具体约会（开会、航班）填 false，固定那一瞬间。没钟点的填 true"
            )
        )

        /** 清单（§4.5）：只给建提醒，改清单另有 `change_checklist` —— 整条重写会把勾冲掉 */
        val ITEMS_PROPERTY = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "string"),
            "description" to "这件事底下要带的、要买的、要准备的几样（清单），一样一项，每项最多 ${Checklist.MAX_CHARS} 个字，最多 ${Checklist.MAX_ITEMS} 样。" +
                "收拾行李、出门、采购、搬家这类，他没列也直接替他列上常用的（护照、充电器……），不要先问；他说了要哪几样就照他的。" +
                "和东西无关的事（吃药、开会、交房租）填 []"
        )

        /** 清单那一项；没填是空的 */
        fun itemsOf(arguments: String): List<String> = runCatching {
            mapper.readTree(arguments).path("items").takeIf { it.isArray }
                ?.mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        }.getOrDefault(emptyList())

        /** 参数 JSON → [ReminderPlan]，缺必填字段或不是 JSON 就是 null */
        fun planOf(arguments: String): ReminderPlan? = runCatching {
            val o = mapper.readTree(arguments)
            ReminderPlan(
                title = o.text("title") ?: return null,
                day = o.text("day") ?: return null,
                times = o.path("times").takeIf { it.isArray }?.mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty(),
                basis = o.text("basis").orEmpty(),
                note = o.text("note"),
                rrule = o.text("rrule"),
                remindBefore = o.path("remindBefore").takeIf { it.isArray }?.mapNotNull { n -> n.takeIf { it.canConvertToInt() }?.asInt() }.orEmpty(),
                ring = o.text("ring") ?: return null,
                nag = o.text("nag") ?: return null,
                wallClockAnchored = o.path("wallClockAnchored").asBoolean(true)
            )
        }.getOrNull()

        /** 推算依据，编辑页「依据」那一段。11-16 以前的旧参数也有这个字段，单读它不用整条认得 */
        fun basisOf(arguments: String): String? = runCatching { mapper.readTree(arguments).text("basis") }.getOrNull()

        private fun JsonNode.text(field: String): String? =
            get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

        private fun string(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun nullableString(desc: String) = mapOf("type" to listOf("string", "null"), "description" to desc)
        private fun boolean(desc: String) = mapOf("type" to "boolean", "description" to desc)
        private fun enum(values: List<String>, desc: String) = mapOf("type" to "string", "enum" to values, "description" to desc)
    }
}
