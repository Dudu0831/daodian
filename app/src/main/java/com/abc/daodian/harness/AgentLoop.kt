package com.abc.daodian.harness

import com.abc.daodian.harness.context.ContextPolicy
import com.abc.daodian.harness.context.LastTurns
import com.abc.daodian.harness.llm.LlmClient
import com.abc.daodian.harness.llm.LlmEvent
import com.abc.daodian.harness.llm.LlmException
import com.abc.daodian.harness.llm.LlmRequest
import com.abc.daodian.harness.llm.StepOutput
import com.abc.daodian.harness.permission.Approval
import com.abc.daodian.harness.permission.PermissionGate
import com.abc.daodian.harness.tool.ToolContext
import com.abc.daodian.harness.tool.ToolOutcome
import com.abc.daodian.harness.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * ReAct 循环：调模型 → 模型要调工具就执行 → 结果回传 → 再调模型，直到它只说话不调工具。
 *
 * 循环不认识任何具体工具，也不管界面；它只负责三件事：
 *  - 每一步按 [context] 选出要喂的历史
 *  - 工具执行前过授权闸门（[PermissionGate]）
 *  - 边跑边把每一项写进 [Session]，中途被「停」也不丢已经发生的事
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val system: String,
    private val context: ContextPolicy = LastTurns(),
    private val maxSteps: Int = DEFAULT_MAX_STEPS
) {

    init {
        require(maxSteps >= 1)
    }

    fun run(session: Session, input: String, now: ZonedDateTime, permissions: PermissionGate): Flow<AgentEvent> = flow {
        session.begin(Item.UserMessage(input, now))
        val toolContext = ToolContext(now, input)
        try {
            for (step in 0 until maxSteps) {
                val output = try {
                    callModel(step, session)
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
                // 先把这一步的调用全部记上，再逐个执行 —— 中途被停、改口，没轮到的由 finally 统一补结果
                output.toolCalls.forEach(session::append)
                output.toolCalls.forEach { call ->
                    val invoked = invoke(call, permissions, toolContext)
                    session.append(invoked.result)
                    if (invoked.redirected) {
                        emit(AgentEvent.Finished(session.current, StopReason.REDIRECTED))
                        return@flow
                    }
                }
            }
            emit(AgentEvent.Finished(session.current, StopReason.STEP_LIMIT))
        } finally {
            session.closeDanglingCalls(ABORTED)
        }
    }

    /** 调一次模型，把过程事件转发出去，返回这一步的产出 */
    private suspend fun FlowCollector<AgentEvent>.callModel(step: Int, session: Session): StepOutput {
        val request = LlmRequest(
            system = system,
            input = context.select(session.turns).flatMap { it.items },
            tools = tools.all
        )
        var output: StepOutput? = null
        llm.step(request).collect { event ->
            if (event is LlmEvent.Done) output = event.output
            emit(AgentEvent.Model(step, event))
        }
        return output ?: throw LlmException("模型调用结束了，但没有给出结果")
    }

    /** 一次调用的下场：要记进历史的结果，以及用户是不是借这次授权改了口（改口 = 这一轮就此收住） */
    private class Invoked(val result: Item.ToolResult, val redirected: Boolean = false)

    private suspend fun FlowCollector<AgentEvent>.invoke(
        call: Item.ToolCall,
        permissions: PermissionGate,
        context: ToolContext
    ): Invoked {
        val tool = tools[call.name]
            ?: return Invoked(Item.ToolResult(call.callId, "没有叫 ${call.name} 的工具。", ok = false))

        if (permissions.needsApproval(tool)) emit(AgentEvent.AwaitingApproval(call))
        when (permissions.decide(call, tool)) {
            Approval.Approved -> Unit
            Approval.Denied -> {
                emit(AgentEvent.ToolDenied(call))
                return Invoked(Item.ToolResult(call.callId, DENIED, ok = false))
            }
            is Approval.Redirected -> {
                emit(AgentEvent.ToolDenied(call))
                return Invoked(Item.ToolResult(call.callId, REDIRECTED, ok = false), redirected = true)
            }
        }

        val outcome = try {
            tool.execute(call.arguments, context)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ToolOutcome("工具执行出错：${t.javaClass.simpleName}: ${t.message}", ok = false)
        }
        emit(AgentEvent.ToolFinished(call, outcome))
        return Invoked(Item.ToolResult(call.callId, outcome.output, outcome.ok, outcome.ref))
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 6

        /** 回给模型的固定话术。写成它能照着往下接的样子 */
        const val DENIED = "用户没有同意这次操作，它没有执行。不要换个参数再试，除非用户自己改口。"
        const val ABORTED = "这次调用没有执行：用户中途叫停了这一轮。"
        const val REDIRECTED = "用户没有同意这次操作，它没有执行 —— 他改口了，要怎么办见他的下一句话。"
    }
}
