package com.abc.daodian.intake.assistant

import android.content.Context
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.runtime.kit.Kit
import com.abc.daodian.runtime.tool.Tool

/**
 * 通知监听层给助手的：一个平时收着的工具包（建、停、查「听通知」规则的六个工具连同用法，[IntakeTools.kit]）和它们的痕（DESIGN.md §12.2）。
 * 规则只在对话里建；建规则是偶尔才做的事，所以没有常驻的工具
 */
object IntakePlugin : AssistantPlugin {

    /** 和 `IntakeModule.ID` 一样 */
    override val id = "intake"

    override fun tools(context: Context): List<Tool> = emptyList()

    override fun kits(context: Context): List<Kit> = listOf(IntakeTools.kit(IntakeDesk(context)))

    override fun trace(call: ToolTrace): TraceView? = IntakeTrace.of(call)
}
