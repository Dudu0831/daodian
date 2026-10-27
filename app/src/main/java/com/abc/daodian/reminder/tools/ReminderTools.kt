package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext

/**
 * 提醒工具怎么分给不同的 agent（同 `ledger/tools/LedgerTools`）。落库那一下由用的一方给，这里不要 Context ——
 * 测试里照样建得出来，`AgentsTest` 拿的就是这一份，和真机上接的是同一套。
 *
 * - 助手（对话页、速记）：建提醒，落库时原话记成来源（`reminder/assistant/ReminderPlugin`）
 * - 派活：也只有建提醒一样，落库时写上是谁派的、一律响铃（`relay/Relay`）
 * - 提醒子代理（通知管家交来的）：也只有建提醒一样，落库时写上是哪条通知来的（`notice/ReminderNoticeTarget`）
 */
object ReminderTools {

    fun forAssistant(commit: suspend (ReminderPlan, ToolContext) -> Long): List<Tool> = listOf(CreateReminderTool(commit))

    fun forRelay(commit: suspend (ReminderPlan, ToolContext) -> Long): List<Tool> = listOf(CreateReminderTool(commit))

    fun forNotice(commit: suspend (ReminderPlan, ToolContext) -> Long): List<Tool> = listOf(CreateReminderTool(commit))
}
