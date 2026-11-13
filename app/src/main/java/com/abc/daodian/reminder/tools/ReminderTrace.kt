package com.abc.daodian.reminder.tools

import com.abc.daodian.assistant.plugin.PartialArgs
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceText
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.domain.ReminderText
import com.abc.daodian.reminder.domain.Rrule
import com.abc.daodian.shared.format.Format

/**
 * 提醒在对话里留的痕：建的「✓ 提醒 9月24日 周四 08:00 · 带伞 ›」、改的「✓ 提醒 改成 明天 …」，点了去编辑页；
 * 删的「✓ 提醒 删了 · 喝水」，点不进去。见 DESIGN.md §6.6
 *
 * 轻提醒的多一截，写在标题前面：「每天 14:00 · 轻提醒 · 喝水」—— 痕只有一行，标题长了被截断时它还在。
 */
object ReminderTrace {

    fun of(call: ToolTrace): TraceView? = when (call.tool) {
        CreateReminderTool.NAME -> plan(call, working = "在记提醒", failed = "没建成", prefix = "没建。")
        UpdateReminderTool.NAME -> plan(call, working = "在改提醒", failed = "没改成", prefix = "没改。", verb = "改成")
        DeleteRemindersTool.NAME -> deleted(call)
        else -> null
    }

    /** 建和改：写的都是这一条现在什么时候响。改的多「改成」两个字，点了一样去编辑页 */
    private fun plan(call: ToolTrace, working: String, failed: String, prefix: String, verb: String? = null): TraceView {
        val plan = if (call.ok) CreateReminderTool.planOf(call.arguments) else null
        return TraceView(
            working = working,
            settled = if (call.failed) failed else "提醒",
            text = when {
                call.failed -> TraceText.reasonOf(call.output, prefix)
                plan != null -> (verb?.let { "$it " } ?: "") +
                    listOfNotNull(whenOf(plan), "轻提醒".takeIf { plan.gentle && !plan.allDay }, plan.title).joinToString(" · ")
                else -> PartialArgs.text(call.arguments, "title").orEmpty()
            },
            route = call.ref?.takeIf { call.ok }?.let { ReminderRoutes.edit(it) }
        )
    }

    /** 删：「删了 · 喝水」；几条的「删了 3 条」，细目逐条列。删掉的点不进去 */
    private fun deleted(call: ToolTrace): TraceView {
        // 结果第一行：「删了 2 条：喝水；交房租」
        val head = call.output?.lineSequence()?.firstOrNull().orEmpty()
        val titles = head.substringAfter('：', "").split('；').map { it.trim() }.filter { it.isNotEmpty() }
        return TraceView(
            working = "在删提醒",
            settled = if (call.failed && titles.isEmpty()) "没删成" else "提醒",
            text = when {
                call.failed && titles.isEmpty() -> TraceText.reasonOf(call.output, "没删。")
                titles.isEmpty() -> ""
                titles.size == 1 -> "删了 · ${titles.single()}"
                else -> "删了 ${titles.size} 条"
            },
            lines = if (titles.size > 1) titles.map { "删了 · $it" } else emptyList()
        )
    }

    /**
     * 什么时候响，写人话。重复的报「每天 08:00」，一次性的报完整日期；
     * 当天事项没有钟点：「9月18日 周五 · 今天之内」/「每天 · 当天之内」
     */
    private fun whenOf(plan: ReminderPlan): String {
        val rrule = Rrule.human(plan.rrule)
        val dueDay = if (plan.allDay) runCatching { PlanValidator.dueDayOf(plan) }.getOrNull() else null
        val millis = runCatching { PlanValidator.triggerMillis(plan) }.getOrNull()
        return when {
            dueDay != null && rrule != null -> "$rrule · 当天之内"
            // 离得远的，dayTaskWhen 给的是「9月30日之内」—— 前面已经写了日期，不再说一遍
            dueDay != null -> "${Format.humanDay(dueDay)} · " +
                ReminderText.dayTaskWhen(dueDay).let { if (it.startsWith("${dueDay.monthValue}月")) "当天之内" else it }
            millis == null -> Format.humanDateTime(plan.firstTriggerAt)
            rrule != null -> "$rrule ${Format.clock(millis)}"
            else -> Format.humanDateTime(millis)
        }
    }
}
