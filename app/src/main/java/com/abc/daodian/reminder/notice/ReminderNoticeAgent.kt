package com.abc.daodian.reminder.notice

import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.tools.ReminderTools
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentInfo
import com.abc.daodian.runtime.tool.ToolContext

/**
 * 提醒子代理：通知管家交来的一条通知 + 附言 → 后台一轮模型 → 建成提醒（DESIGN.md §12.5）。
 * 只带 `create_reminder` 一个工具，问不了你。交来、结果怎么记归 [ReminderNoticeTarget]；它说什么归 [ReminderNoticePrompt]。
 * 取代了以前的派活 agent（`relay`）
 */
object ReminderNoticeAgent : AgentInfo {

    override val id = "reminder-notice"
    override val label = "提醒子代理"
    override val owner = "reminder"
    override val wakes = "后台：通知管家交来一条"
    override val prompts = listOf(ReminderNoticePrompt.PROMPT)

    /** [commit]：落库那一下由 [ReminderNoticeTarget] 给（写上是哪条通知来的） */
    fun build(commit: suspend (ReminderPlan, ToolContext) -> Long) =
        Agent(this, ReminderNoticePrompt.PROMPT, ReminderTools.forNotice(commit), maxSteps = 3)
}
