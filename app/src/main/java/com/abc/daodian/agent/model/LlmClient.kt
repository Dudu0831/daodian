package com.abc.daodian.agent.model

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.tool.Tool
import kotlinx.coroutines.flow.Flow

/** 一次模型调用要带的全部东西。无状态：历史每次都由调用方完整给出，不依赖服务端会话 */
data class LlmRequest(
    val system: String,
    val input: List<Item>,
    val tools: Collection<Tool>
)

/** 一次调用的产出 */
data class StepOutput(
    /** 说给用户听的正文，可能为空（只调了工具） */
    val text: String,
    /** 要调的工具，按模型给出的顺序 */
    val toolCalls: List<Item.ToolCall>,
    /** 思考过程。只用于展示，不进历史 */
    val reasoning: String
)

/** 一次调用过程中的事件。整条流以恰好一个 [Done] 结束；失败则抛 [LlmException] */
sealed interface LlmEvent {
    data class Reasoning(val delta: String) : LlmEvent
    data class Text(val delta: String) : LlmEvent
    data class ToolStarted(val callId: String, val name: String) : LlmEvent
    data class ToolArgs(val callId: String, val delta: String) : LlmEvent

    /** 流式没走通、退回了一次性请求。界面收到后应把这一步已经吐出来的半截字擦掉 */
    data object FellBack : LlmEvent

    data class Done(val output: StepOutput) : LlmEvent
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 只管「调一次模型」。不认识任何具体工具，也不管循环 */
interface LlmClient {
    /** 用的是哪个模型。工具落库时记成「谁解析的」，不影响调用 */
    val model: String get() = ""

    fun step(request: LlmRequest): Flow<LlmEvent>
}
