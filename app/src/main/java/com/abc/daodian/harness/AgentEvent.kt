package com.abc.daodian.harness

import com.abc.daodian.harness.llm.LlmEvent
import com.abc.daodian.harness.tool.ToolOutcome

/** [AgentLoop.run] 一轮里发生的事。以恰好一个 [Finished] 或 [Failed] 结束（被取消时两者都没有） */
sealed interface AgentEvent {

    /** 第 [step] 次调模型的过程事件（从 0 数）。界面据此逐字画 */
    data class Model(val step: Int, val event: LlmEvent) : AgentEvent

    /** 这个调用要用户点头才执行，循环在等 [com.abc.daodian.harness.permission.Approver] */
    data class AwaitingApproval(val call: Item.ToolCall) : AgentEvent

    /** 用户没同意，工具没执行 */
    data class ToolDenied(val call: Item.ToolCall) : AgentEvent

    /** 工具执行完了（成没成看 [outcome] 的 ok） */
    data class ToolFinished(val call: Item.ToolCall, val outcome: ToolOutcome) : AgentEvent

    data class Finished(val turn: Turn, val stop: StopReason) : AgentEvent

    data class Failed(val turn: Turn, val reason: String, val cause: Throwable? = null) : AgentEvent
}

enum class StopReason {
    /** 模型不再调工具，给出了回答 */
    ANSWERED,

    /** 撞到步数上限。多半是模型在原地打转 */
    STEP_LIMIT
}
