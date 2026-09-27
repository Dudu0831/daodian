package com.abc.daodian.agent.engine

import com.abc.daodian.agent.engine.context.Background
import com.abc.daodian.agent.engine.context.ContextPolicy
import com.abc.daodian.agent.engine.context.LastTurns
import com.abc.daodian.agent.engine.context.Preamble
import com.abc.daodian.agent.model.LlmClient
import com.abc.daodian.agent.model.LlmEvent
import com.abc.daodian.agent.model.LlmException
import com.abc.daodian.agent.model.LlmRequest
import com.abc.daodian.agent.model.StepOutput
import com.abc.daodian.agent.engine.ask.Asker
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolOutcome
import com.abc.daodian.agent.engine.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * ReAct 循环：调模型 → 模型要调工具就执行 → 结果回传 → 再调模型，直到它只说话不调工具。
 *
 * 循环不认识任何具体工具，也不管界面；它只负责两件事：
 *  - 每一步按 [context] 选出要喂的历史，前面垫上 [Background] 给的那段（记忆、摘要，DESIGN.md §6.9）
 *  - 边跑边把每一项写进 [Session]，中途被「停」也不丢已经发生的事
 *
 * 写操作直接执行，不先问用户（DESIGN.md §6.1）。要问人是模型自己的决定：它调 `ask_user`，
 * 那个工具通过 [Asker] 找界面、挂起到用户答完。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val system: String,
    private val context: ContextPolicy = LastTurns(),
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** 每次真要发请求前看一眼发的是什么（垫了什么、原样带了哪几轮）。只给调试记录用，见 ContextTrace */
    private val onRequest: (preamble: Preamble?, sent: List<Turn>, request: LlmRequest) -> Unit = { _, _, _ -> }
) {

    init {
        require(maxSteps >= 1)
    }

    /**
     * [asker]：模型要问人时找谁（`ask_user`）。
     * [trigger]：这一轮是 app 自己发起的（见 [Item.UserMessage.trigger]），不是用户说的话。
     * [background]：历史前面垫什么。对话页垫记忆 + 摘要，桌面速记只垫记忆，后台整理什么都不垫
     */
    fun run(
        session: Session,
        input: String,
        now: ZonedDateTime,
        asker: Asker = Asker.NONE,
        trigger: Boolean = false,
        background: Background = Background.NONE
    ): Flow<AgentEvent> = flow {
        session.begin(Item.UserMessage(input, now, trigger))
        val toolContext = ToolContext(now, input, asker, model = llm.model)
        try {
            for (step in 0 until maxSteps) {
                val output = try {
                    callModel(step, session, background)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    emit(AgentEvent.Failed(session.current, t.message ?: t.javaClass.simpleName, t))
                    return@flow
                }

                if (output.text.isNotEmpty()) session.append(Item.AssistantMessage(output.text))
                if (output.toolCalls.isEmpty()) {
                    emit(AgentEvent.Finished(session.current, StopReason.ANSWERED))
                    return@flow
                }
                // 先把这一步的调用全部记上，再逐个执行 —— 中途被停，没轮到的由 finally 统一补结果
                output.toolCalls.forEach(session::append)
                output.toolCalls.forEach { call -> session.append(invoke(call, toolContext)) }
            }
            emit(AgentEvent.Finished(session.current, StopReason.STEP_LIMIT))
        } finally {
            session.closeDanglingCalls { tools[it.name]?.abortedOutput ?: ABORTED }
        }
    }

    /** 调一次模型，把过程事件转发出去，返回这一步的产出 */
    private suspend fun FlowCollector<AgentEvent>.callModel(step: Int, session: Session, background: Background): StepOutput {
        val preamble = background.of(session)
        val all = session.turns
        // 摘要盖住的轮次不再原样喂；当前这一轮无论如何都在
        val turns = if (preamble == null) all else all.filter { it.id > preamble.afterTurnId || it === all.last() }
        val sent = context.select(turns)
        val request = LlmRequest(
            system = system,
            input = sent.flatMap { it.items },
            tools = tools.all,
            background = preamble?.text
        )
        onRequest(preamble, sent, request)
        var output: StepOutput? = null
        llm.step(request).collect { event ->
            if (event is LlmEvent.Done) output = event.output
            emit(AgentEvent.Model(step, event))
        }
        return output ?: throw LlmException("模型调用结束了，但没有给出结果")
    }

    private suspend fun FlowCollector<AgentEvent>.invoke(call: Item.ToolCall, context: ToolContext): Item.ToolResult {
        val tool = tools[call.name]
            ?: return Item.ToolResult(call.callId, "没有叫 ${call.name} 的工具。", ok = false)

        emit(AgentEvent.ToolStarting(call))
        val outcome = try {
            tool.execute(call.arguments, context.copy(call = call))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ToolOutcome("工具执行出错：${t.javaClass.simpleName}: ${t.message}", ok = false)
        }
        emit(AgentEvent.ToolFinished(call, outcome))
        return Item.ToolResult(call.callId, outcome.output, outcome.ok, outcome.ref)
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 6

        /** 回给模型的固定话术。写成它能照着往下接的样子 */
        const val ABORTED = "这次调用没有执行：用户中途叫停了这一轮。"
    }
}
