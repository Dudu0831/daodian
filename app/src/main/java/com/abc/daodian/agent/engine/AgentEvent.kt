package com.abc.daodian.agent.engine

import com.abc.daodian.agent.model.LlmEvent
import com.abc.daodian.agent.engine.tool.ToolOutcome

/** [AgentLoop.run] 一轮里发生的事。以恰好一个 [Finished] 或 [Failed] 结束（被取消时两者都没有） */
sealed interface AgentEvent {

    /** 第 [step] 次调模型的过程事件（从 0 数）。界面据此逐字画 */
    data class Model(val step: Int, val event: LlmEvent) : AgentEvent

    /** 要开始执行这个调用了，参数已经收全（一次性请求没有流式事件，界面靠这一下知道有这个调用） */
    data class ToolStarting(val call: Item.ToolCall) : AgentEvent

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
