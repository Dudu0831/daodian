package com.abc.daodian.intake.assistant

import com.abc.daodian.assistant.plugin.PartialArgs
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.assistant.plugin.TraceText
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.intake.IntakeRoutes

/**
 * 建、停规则留的痕（DESIGN.md §6.6、§12.2）：只写规则名和预演的数 ——「监听 · 快递到了提醒取 · 对得上 1 条」。
 * 硬条件、软规则可能一大段，不上痕，点进规则页看。字从工具回给模型的话里取（第一行的名字、第三行的数）
 */
object IntakeTrace {

    fun of(call: ToolTrace): TraceView? = when (call.tool) {
        IntakeTools.ADD_RULE -> TraceView(
            working = "在建监听",
            settled = if (call.failed) "没建成" else "监听",
            text = when (call.state) {
                TraceState.RUNNING -> PartialArgs.text(call.arguments, "name").orEmpty()
                // 第一行「建好了：r5「名字」。」，第三行是预演（冒号后面的细目不上痕）
                TraceState.OK -> call.output.orEmpty().lines().let { l ->
                    listOfNotNull(nameOf(l.firstOrNull()), l.getOrNull(2)?.substringBefore('：')?.removePrefix("现在通知栏和收件箱里"))
                        .joinToString(" · ")
                }
                TraceState.FAILED -> TraceText.reasonOf(call.output, "没建：")
            },
            route = call.ref?.takeIf { call.ok }?.let(IntakeRoutes::rule)
        )
        IntakeTools.STOP_RULE -> TraceView(
            working = "在停监听",
            settled = if (call.failed) "没停成" else "停了监听",
            text = when (call.state) {
                TraceState.RUNNING -> ""
                TraceState.OK -> call.output.orEmpty().removePrefix("停了：").removeSuffix("。")
                TraceState.FAILED -> TraceText.reasonOf(call.output, "没停：")
            },
            route = call.ref?.takeIf { call.ok }?.let(IntakeRoutes::rule)
        )
        else -> null
    }

    /** 「建好了：r5「快递到了提醒取」。」里的名字 */
    private fun nameOf(line: String?): String? =
        line?.substringAfter('「', "")?.substringBeforeLast('」', "")?.takeIf { it.isNotBlank() }
}
