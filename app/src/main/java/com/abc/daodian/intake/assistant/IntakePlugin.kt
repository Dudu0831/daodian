package com.abc.daodian.intake.assistant

import android.content.Context
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.runtime.tool.Tool

/**
 * 通知监听层给助手的：建、停、查「听通知」规则的六个工具和它们的痕（DESIGN.md §12.2）。
 * 规则只在对话里建；怎么用这几个工具写在助手的提示词里（`assistant/AssistantPrompt` 的「听通知」一段）
 */
object IntakePlugin : AssistantPlugin {

    /** 和 `IntakeModule.ID` 一样 */
    override val id = "intake"

    override fun tools(context: Context): List<Tool> = IntakeTools.of(IntakeDesk(context))

    override fun trace(call: ToolTrace): TraceView? = IntakeTrace.of(call)
}
