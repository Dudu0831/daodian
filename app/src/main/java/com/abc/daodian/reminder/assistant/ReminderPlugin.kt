package com.abc.daodian.reminder.assistant

import android.content.Context
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.reminder.ReminderModule
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.tools.ReminderTools
import com.abc.daodian.reminder.tools.ReminderTrace
import com.abc.daodian.runtime.tool.Tool

/**
 * 提醒给助手的：`create_reminder`、它留的痕、空状态的例句、连不上模型时「手动填一条」去哪。
 * 怎么用 `create_reminder` 写在助手自己的提示词里（`assistant/AssistantPrompt`「提醒的规则」）。
 */
object ReminderPlugin : AssistantPlugin {

    override val id = ReminderModule.ID

    override fun tools(context: Context): List<Tool> {
        val app = context.applicationContext
        return ReminderTools.forAssistant { plan, ctx -> Reminders.commitPlan(app, ctx.userInput, plan, ctx.model) }
    }

    override fun trace(call: ToolTrace): TraceView? = ReminderTrace.of(call)

    override val examples = listOf(
        "三分钟后提醒我喝水。",
        "下周三下午三点，交房租。",
        "每天早上八点提醒我吃药。",
        "这周五下班前把周报发出去。"
    )

    override val manualEntry = ReminderRoutes.NEW
}
