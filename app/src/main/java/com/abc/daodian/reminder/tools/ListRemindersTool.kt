package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.reminder.data.untimed
import com.abc.daodian.reminder.domain.spec
import com.abc.daodian.reminder.domain.ReminderText
import com.abc.daodian.reminder.domain.Rrule
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.abc.daodian.shared.format.Format
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 查提醒：按关键词、日期筛，默认只列还没响的。只读，不留痕。
 *
 * 每条一行人话，后面跟着 `update_reminder` 要的那几个字段的原样值 —— 改提醒是把整条重写一遍，
 * 没改的照这里抄。结果是查的那一刻的样子，几轮之后折掉（§6.9）。
 */
class ListRemindersTool(
    private val desk: ReminderDesk,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : Tool {

    override val name = NAME

    override val effect = ToolEffect.READ

    override val outputGoesStale = true

    override val description =
        "查用户手机上已有的提醒。他问「明天有什么提醒」「我设过喝水的提醒吗」，或者你要改、删某条之前先查它的 # 编号和现在的样子，都用它。" +
            "默认只列还没响的；他问以前的、响过的才把 include_finished 设成 true。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "keyword" to nullableString("在标题、备注、原话里找（「喝水」「房租」）；不限 null"),
            "from" to nullableString("起始日期 YYYY-MM-DD（含），按下一次响的那天算；不限 null"),
            "to" to nullableString("截止日期 YYYY-MM-DD（含）；不限 null"),
            "include_finished" to mapOf(
                "type" to "boolean",
                "description" to "true 时连响过的、完成了的也列（最近的在前）；一般 false"
            ),
            "limit" to mapOf("type" to listOf("integer", "null"), "description" to "最多几条，默认 30，最多 100")
        ),
        "required" to listOf("keyword", "from", "to", "include_finished", "limit"),
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return ToolOutcome("参数不是合法的 JSON", ok = false)
        val zone = zone()
        val today = context.now.withZoneSameInstant(zone).toLocalDate()
        val keyword = o.text("keyword")
        val from = o.day("from")
        val to = o.day("to")
        val withFinished = o.path("include_finished").asBoolean(false)
        val limit = (o.get("limit")?.takeIf { it.canConvertToInt() }?.asInt() ?: 30).coerceIn(1, 100)

        val all = desk.all()
        val live = all.filter { it.status == ReminderStatus.SCHEDULED }.sortedBy { it.nextTriggerAt }
        val finished = if (withFinished) all.filter { it.status != ReminderStatus.SCHEDULED }.sortedByDescending { it.updatedAt } else emptyList()
        val hits = (live + finished).filter { r ->
            val day = dayOf(r)
            (keyword == null || listOfNotNull(r.title, r.note, r.rawInput).any { it.contains(keyword, ignoreCase = true) }) &&
                (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to))
        }
        if (hits.isEmpty()) {
            return ToolOutcome(if (withFinished) "没有符合条件的提醒。" else "没有符合条件的、还没响的提醒。", ok = true)
        }
        val shown = hits.take(limit)
        val out = "共 ${hits.size} 条" + (if (hits.size > shown.size) "，列了前 ${shown.size} 条" else "") + "：\n" +
            shown.joinToString("\n") { line(it, zone, today) }
        return ToolOutcome(out, ok = true)
    }

    companion object {
        const val NAME = "list_reminders"

        private val mapper = ObjectMapper()

        /**
         * 一条提醒给模型看的一行：「#12 喝水 · 每天 09:00、15:00 · 下次 11月14日 周五 09:00 · 只发通知 | day=… times=[…] …」。
         * 竖线后面是 `update_reminder` 照抄的原样值，六件事都在（§4.6）
         */
        fun line(r: Reminder, zone: ZoneId, today: LocalDate): String {
            val spec = r.spec()
            // 拖过来的（每天再提着的）：那天已经过了，抄回去会被闸门当成过去。一次性的抄今天，重复的抄今天或以后的头一次
            val day = if (!spec.day.isBefore(today)) spec.day else nextOnOrAfter(spec.rrule, spec.day, today, zone)
            val human = listOfNotNull(
                "#${r.id} ${r.title}",
                ReminderText.whenOf(spec, today),
                "拖了 ${ChronoUnit.DAYS.between(spec.day, today)} 天".takeIf { r.untimed && spec.day.isBefore(today) && spec.rrule == null },
                "下次 ${Format.humanDateTime(r.nextTriggerAt, zone)}".takeIf { r.status == ReminderStatus.SCHEDULED && !r.untimed },
                spec.remindBefore.takeIf { it.isNotEmpty() }?.let { "提前 ${it.joinToString("、")} 天提一下" },
                "只发通知".takeIf { !r.untimed && spec.ring == Ring.NOTICE },
                when (spec.nag) {
                    Nag.NONE -> null
                    Nag.SOON -> "没点完成 30 分钟后再提"
                    Nag.DAILY -> "没点完成每晚再提"
                },
                when (r.status) {
                    ReminderStatus.SCHEDULED -> null
                    ReminderStatus.FIRED -> "响过了、还没点完成"
                    ReminderStatus.DONE -> "完成了"
                    ReminderStatus.CANCELLED -> "不再提醒了"
                },
                r.note?.takeIf { it.isNotBlank() }?.let { "备注：$it" }
            ).joinToString(" · ")
            return "$human | day=$day times=[${spec.times.joinToString(",") { it.toString().take(5) }}] rrule=${spec.rrule ?: "null"} " +
                "remindBefore=[${spec.remindBefore.joinToString(",")}] ring=${spec.ring.name.lowercase()} nag=${spec.nag.name.lowercase()} " +
                "wallClockAnchored=${spec.wallClockAnchored}"
        }

        /** 重复的从 [from] 往后数，头一个不早于 [today] 的；不重复（或规则排不出下一次）的就是 [today] */
        private fun nextOnOrAfter(rrule: String?, from: LocalDate, today: LocalDate, zone: ZoneId): LocalDate {
            var at = from.atTime(12, 0).atZone(zone)
            while (at.toLocalDate().isBefore(today)) at = Rrule.nextAfter(rrule, at, zone) ?: return today
            return at.toLocalDate()
        }

        /** 筛日期用的那一天：这一次的那天 */
        private fun dayOf(r: Reminder): LocalDate = r.day

        private fun JsonNode.text(field: String): String? =
            get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonNode.day(field: String): LocalDate? = text(field)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        private fun nullableString(desc: String) = mapOf("type" to listOf("string", "null"), "description" to desc)
    }
}
