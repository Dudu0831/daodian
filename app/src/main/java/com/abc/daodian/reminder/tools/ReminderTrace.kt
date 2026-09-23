package com.abc.daodian.reminder.tools

import com.abc.daodian.agent.feature.PartialArgs
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceText
import com.abc.daodian.agent.feature.TraceView
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.shared.format.Format

/** 建提醒在对话里留的痕：「✓ 提醒 9月24日 周四 08:00 · 带伞 ›」，点了去编辑页。见 DESIGN.md §6.9 */
object ReminderTrace {

    fun of(call: ToolTrace): TraceView? {
        if (call.tool != CreateReminderTool.NAME) return null
        val plan = if (call.ok) CreateReminderTool.planOf(call.arguments) else null
        return TraceView(
            working = "在记提醒",
            settled = if (call.failed) "没建成" else "提醒",
            text = when {
                call.failed -> TraceText.reasonOf(call.output, "没建。")
                plan != null -> "${whenOf(plan)} · ${plan.title}"
                else -> PartialArgs.text(call.arguments, "title").orEmpty()
            },
            route = call.ref?.takeIf { call.ok }?.let { ReminderRoutes.edit(it) }
        )
    }

    /**
     * 什么时候响，写人话。重复的报「每天 08:00」，一次性的报完整日期；
     * 当天事项没有钟点：「9月18日 周五 · 今天之内」/「每天 · 当天之内」
     */
    private fun whenOf(plan: ReminderPlan): String {
        val rrule = Format.humanRrule(plan.rrule)
        val dueDay = if (plan.allDay) runCatching { PlanValidator.dueDayOf(plan) }.getOrNull() else null
        val millis = runCatching { PlanValidator.triggerMillis(plan) }.getOrNull()
        return when {
            dueDay != null && rrule != null -> "$rrule · 当天之内"
            // 离得远的，dayTaskWhen 给的是「9月30日之内」—— 前面已经写了日期，不再说一遍
            dueDay != null -> "${Format.humanDay(dueDay)} · " +
                Format.dayTaskWhen(dueDay).let { if (it.startsWith("${dueDay.monthValue}月")) "当天之内" else it }
            millis == null -> Format.humanDateTime(plan.firstTriggerAt)
            rrule != null -> "$rrule ${Format.clock(millis)}"
            else -> Format.humanDateTime(millis)
        }
    }
}
