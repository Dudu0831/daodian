package com.abc.daodian.intake.assistant

import com.abc.daodian.assistant.plugin.PartialArgs
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.assistant.plugin.TraceText
import com.abc.daodian.assistant.plugin.TraceView

/**
 * 建、停规则留的痕（DESIGN.md §6.6、§12.2）：硬条件和软规则写全，带上预演的数 ——
 * 「监听 · 短信、菜鸟（含 取件码 / 驿站）→ 快递到了 18:00 提醒我去取 · 现在通知栏和收件箱里对得上 1 条」。
 * 字从工具回给模型的话里取（第二、三行），这里拿不到 Context 去问 app 叫什么。规则页（第 7 步）有了再加点进去的路由
 */
object IntakeTrace {

    fun of(call: ToolTrace): TraceView? = when (call.tool) {
        IntakeTools.ADD_RULE -> TraceView(
            working = "在建监听",
            settled = if (call.failed) "没建成" else "监听",
            text = when (call.state) {
                TraceState.RUNNING -> PartialArgs.text(call.arguments, "name").orEmpty()
                // 第二行是硬条件 → 软规则，第三行是预演（冒号后面的细目不上痕）
                TraceState.OK -> call.output.orEmpty().lines().drop(1).let { l ->
                    listOfNotNull(l.getOrNull(0), l.getOrNull(1)?.substringBefore('：')).joinToString(" · ")
                }
                TraceState.FAILED -> TraceText.reasonOf(call.output, "没建：")
            }
        )
        IntakeTools.STOP_RULE -> TraceView(
            working = "在停监听",
            settled = if (call.failed) "没停成" else "停了监听",
            text = when (call.state) {
                TraceState.RUNNING -> ""
                TraceState.OK -> call.output.orEmpty().removePrefix("停了：").removeSuffix("。")
                TraceState.FAILED -> TraceText.reasonOf(call.output, "没停：")
            }
        )
        else -> null
    }
}
