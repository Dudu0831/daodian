package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import java.time.ZoneId

/**
 * 提醒工具怎么分给不同的 agent（同 `ledger/tools/LedgerTools`）。落库那一下由用的一方给，这里不要 Context ——
 * 测试里照样建得出来，`AgentsTest` 拿的就是这一份，和真机上接的是同一套。
 *
 * - 助手（对话页、速记）：建、改清单、查、改、不再提醒、删，落库时原话记成来源（`reminder/assistant/ReminderPlugin` 给 [ReminderDesk]）
 * - 提醒子代理（通知管家交来的）：也只有建提醒一样，落库时写上是哪条通知来的（`notice/ReminderNoticeTarget`）
 */
object ReminderTools {

    fun forAssistant(desk: ReminderDesk, zone: () -> ZoneId = { ZoneId.systemDefault() }): List<Tool> = listOf(
        CreateReminderTool(desk::create),
        ChangeChecklistTool(desk),
        ListRemindersTool(desk, zone),
        UpdateReminderTool(desk),
        StopRemindersTool(desk),
        DeleteRemindersTool(desk)
    )

    /** 通知交来的不列清单：提示词没讲，参数里也不给（§4.5） */
    fun forNotice(commit: suspend (ReminderSpec, ToolContext) -> Long): List<Tool> =
        listOf(CreateReminderTool({ spec, _, ctx -> commit(spec, ctx) }, withItems = false))
}
