package com.abc.daodian.runtime.engine

import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.ask.Asker
import com.abc.daodian.runtime.context.Background
import com.abc.daodian.runtime.context.ContextPolicy
import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.context.Preamble
import com.abc.daodian.runtime.model.LlmClient
import com.abc.daodian.runtime.model.LlmEvent
import com.abc.daodian.runtime.model.LlmException
import com.abc.daodian.runtime.model.LlmRequest
import com.abc.daodian.runtime.model.StepOutput
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolOutcome
import com.abc.daodian.runtime.tool.ToolRegistry
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * ReAct 循环：调模型 → 模型要调工具就执行 → 结果回传 → 再调模型，直到它只说话不调工具。
 *
 * 循环不认识任何具体工具，也不管界面；它只负责两件事：
 *  - 每一步按 [context] 选出要喂的历史，前面垫上 [Background] 给的那段（记忆、摘要，DESIGN.md §6.9）
 *  - 边跑边把每一项写进 [Session]，中途被「停」也不丢已经发生的事
 *
 * 写操作直接执行，不先问用户（DESIGN.md §6.1）。要问人是模型自己的决定：它调 `ask_user`，
 * 那个工具通过 [Asker] 找界面、挂起到用户答完。
 *
 * 只在 runtime 里用（`internal`）：app 一律从 [com.abc.daodian.runtime.AgentRuntime] 跑，
 * 读配置、共用客户端、记健康、strict 检查都在那边。
 */
internal class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val system: String,
    private val context: ContextPolicy = LastTurns(),
    private val maxSteps: Int = Agent.DEFAULT_MAX_STEPS,
    /** 每次真要发请求前看一眼发的是什么（垫了什么、原样带了哪几轮）。只给调试记录用，见 ContextTrace */
    private val onRequest: (preamble: Preamble?, sent: List<Turn>, request: LlmRequest) -> Unit = { _, _, _ -> }
) {

    init {
        require(maxSteps >= 1)
    }

    /**
     * [asker]：模型要问人时找谁（`ask_user`）。
     * [trigger]：这一轮是 app 自己发起的（见 [Item.UserMessage.trigger]），不是用户说的话。
     * [background]：历史前面垫什么。对话页垫记忆 + 摘要，桌面速记只垫记忆，后台整理什么都不垫。
     * [images]：随这句话发来的图片（见 [Item.UserMessage.images]），只有对话页会带
     */
    fun run(
        session: Session,
        input: String,
        now: ZonedDateTime,
        asker: Asker = Asker.NONE,
        trigger: Boolean = false,
        background: Background = Background.NONE,
        images: List<String> = emptyList()
    ): Flow<AgentEvent> = flow {
        session.begin(Item.UserMessage(input, now, trigger, images))
        // 工具落库时把原话存成「来源」。只发了图没打字的，来源写成这句，别留空
        val said = input.ifBlank { if (images.isEmpty()) input else "（发了 ${images.size} 张图片，没打字）" }
        val toolContext = ToolContext(now, said, asker, model = llm.model)
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
            session.closeDanglingCalls { tools[it.name]?.abortedOutput ?: Session.ABORTED }
        }
    }

    /**
     * 「测一下」：照着 [session] 接下来说 [input] 时真要发的那样拼请求（同样的垫底、同样挑轮次、同样的工具），
     * 只调一次模型就停 —— 不执行工具、不写进 [session]，也不交给 onRequest（调试记录里只该有真发生的）。
     * 一个字、一个调用都没回来也算没通：聊天里那就是一句空回复
     */
    suspend fun probe(session: Session, input: String, now: ZonedDateTime, background: Background): StepOutput {
        val scratch = Session(session.turns)
        scratch.begin(Item.UserMessage(input, now))
        var output: StepOutput? = null
        llm.step(request(scratch, background).request).collect { if (it is LlmEvent.Done) output = it.output }
        val out = output ?: throw LlmException("模型调用结束了，但没有给出结果")
        if (out.text.isBlank() && out.toolCalls.isEmpty()) throw LlmException("网关回了，但一个字都没有")
        return out
    }

    /** 这一步要发的请求，连同垫了什么、带了哪几轮 */
    private class Fed(val preamble: Preamble?, val sent: List<Turn>, val request: LlmRequest)

    private suspend fun request(session: Session, background: Background): Fed {
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
        return Fed(preamble, sent, request)
    }

    /** 调一次模型，把过程事件转发出去，返回这一步的产出 */
    private suspend fun FlowCollector<AgentEvent>.callModel(step: Int, session: Session, background: Background): StepOutput {
        val fed = request(session, background)
        onRequest(fed.preamble, fed.sent, fed.request)
        var output: StepOutput? = null
        llm.step(fed.request).collect { event ->
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
}
