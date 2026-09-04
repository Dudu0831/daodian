package com.abc.daodian.ai

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ToolChoiceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * 走工具调用，而不是求模型输出 JSON。见 ReminderTool 的说明。
 *
 * 模型有两条出路：
 *  - 信息够 → 调 create_reminder 工具，参数 schema 由服务端强制
 *  - 信息不够 → 不调工具，用文字反问 → 变成 NeedsClarification
 *
 * 两种调用方式：[parseStream] 是主路径（过程可见，见 DESIGN.md §6.7），
 * [parse] 是它的回退 —— 第三方兼容网关不一定实现 SSE，不能赌。
 */
class ToolCallParser(private val profile: ProviderProfile) : StreamingReminderParser {

    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .apiKey(profile.apiKey)
            .baseUrl(profile.baseUrl)
            .build()
    }

    // ---------------- 一次性（回退路径）----------------

    override suspend fun parse(input: String, now: ZonedDateTime, history: List<ChatTurn>): ParseResult =
        withContext(Dispatchers.IO) {
            if (!profile.isConfigured) {
                return@withContext ParseResult.Failed("还没配置供应商：${profile.redacted()}")
            }
            val response = try {
                client.responses().create(params(input, now, history))
            } catch (t: Throwable) {
                Log.e(TAG, "调用失败", t)
                return@withContext ParseResult.Failed("${t.javaClass.simpleName}: ${t.message}", t)
            }
            resultOfResponse(response, now)
        }

    // ---------------- 流式（主路径）----------------

    /**
     * 事件流。发生任何「流没跑起来」的情况都会回退成一次性请求，并先发一个
     * [ParseEvent.FellBack] 让界面把半截字擦掉。
     *
     * 判定「没跑起来」的两种情形：
     *  1. `createStreaming` 直接抛（网关对 `stream:true` 返回 4xx 是最常见的一种）
     *  2. 流开了但一个事件都没吐出来就结束了
     *
     * 流开到一半断掉不算 —— 那时候可能工具参数已经收全了，硬回退等于把已经建成的
     * 提醒再建一遍。这种情况按手上收到的残料判结果。
     */
    override fun parseStream(
        input: String,
        now: ZonedDateTime,
        history: List<ChatTurn>
    ): Flow<ParseEvent> = channelFlow {
        if (!profile.isConfigured) {
            send(ParseEvent.Done(ParseResult.Failed("还没配置供应商：${profile.redacted()}")))
            return@channelFlow
        }

        val acc = Accumulator()
        var streamed = false

        val result: ParseResult? = try {
            withContext(Dispatchers.IO) {
                val stream = client.responses().createStreaming(params(input, now, history))
                // stream() 是阻塞迭代：用户点「停」或离开页面时，不主动 close 就得
                // 一直卡到下一个事件到达才醒得过来。挂在 Job 上，取消即掐连接。
                val closer = currentCoroutineContext().job.invokeOnCompletion {
                    runCatching { stream.close() }
                }
                try {
                    val events = stream.stream().iterator()
                    while (events.hasNext()) {
                        currentCoroutineContext().ensureActive()
                        streamed = true
                        emitEventsOf(events.next(), acc)
                    }
                } finally {
                    closer.dispose()
                    runCatching { stream.close() }
                }
            }
            if (!streamed) {
                Log.w(TAG, "流开了但一个事件都没有，回退一次性请求")
                null
            } else {
                acc.result(now)
            }
        } catch (t: CancellationException) {
            throw t                                     // 用户点了「停」，不是失败
        } catch (t: Throwable) {
            if (streamed) {
                // 半路断了：手上可能已经有完整的工具参数，按残料判，别重来一遍
                Log.e(TAG, "流中断，按已收到的内容判定", t)
                acc.result(now) ?: ParseResult.Failed("${t.javaClass.simpleName}: ${t.message}", t)
            } else {
                Log.w(TAG, "流式没跑起来（${t.javaClass.simpleName}: ${t.message}），回退一次性请求")
                null
            }
        }

        if (result != null) {
            send(ParseEvent.Done(result))
        } else {
            send(ParseEvent.FellBack)
            send(ParseEvent.Done(parse(input, now, history)))
        }
    }

    /** 把一个 SDK 事件翻成我们的事件 + 攒进 [acc]。只认我们用得上的那几类，其余安静忽略 */
    private suspend fun SendChannel<ParseEvent>.emitEventsOf(event: ResponseStreamEvent, acc: Accumulator) {
        event.reasoningTextDelta().orElse(null)?.let {
            acc.reasoning.append(it.delta())
            send(ParseEvent.Reasoning(it.delta()))
        }
        event.reasoningSummaryTextDelta().orElse(null)?.let {
            acc.reasoning.append(it.delta())
            send(ParseEvent.Reasoning(it.delta()))
        }
        event.outputTextDelta().orElse(null)?.let {
            acc.text.append(it.delta())
            send(ParseEvent.Text(it.delta()))
        }
        event.outputItemAdded().orElse(null)?.let { added ->
            added.item().functionCall().orElse(null)?.let { call ->
                acc.toolName = call.name()
                send(ParseEvent.ToolStarted(call.name()))
            }
        }
        event.functionCallArgumentsDelta().orElse(null)?.let {
            acc.toolArgs.append(it.delta())
            send(ParseEvent.ToolArgs(it.delta()))
        }
        // 参数收全的那一下以服务端给的完整串为准 —— delta 拼出来的可能缺尾巴
        event.functionCallArgumentsDone().orElse(null)?.let {
            acc.toolArgs.clear()
            acc.toolArgs.append(it.arguments())
        }
        event.completed().orElse(null)?.let { acc.completed = it.response() }
        event.incomplete().orElse(null)?.let { acc.completed = it.response() }
        event.failed().orElse(null)?.let {
            acc.failure = "模型侧失败：${it.response().error().orElse(null)?.message() ?: "未说明原因"}"
        }
        event.error().orElse(null)?.let { acc.failure = "流错误：${it.message()}" }
    }

    /** 一条流收下来攒的东西 */
    private class Accumulator {
        val reasoning = StringBuilder()
        val text = StringBuilder()
        val toolArgs = StringBuilder()
        var toolName: String? = null
        var completed: Response? = null
        var failure: String? = null

        /** null = 什么都没收到，交给调用方决定要不要回退 */
        fun result(now: ZonedDateTime): ParseResult? {
            // 有完整 Response 就以它为准 —— 和非流式路径共用同一套判定，不写第二份
            completed?.let { return resultOfResponse(it, now) }
            failure?.let { return ParseResult.Failed(it) }

            if (toolName == ReminderTool.NAME && toolArgs.isNotEmpty()) {
                val plan = ReminderTool.toPlan(toolArgs.toString())
                    ?: return ParseResult.Failed("工具参数解析不了：${toolArgs.take(200)}")
                return PlanValidator.validate(plan, now)
            }
            val said = text.toString().trim()
            return if (said.isNotEmpty()) ParseResult.NeedsClarification(said, said) else null
        }
    }

    private fun params(input: String, now: ZonedDateTime, history: List<ChatTurn>): ResponseCreateParams =
        ResponseCreateParams.builder()
            .model(profile.model)
            .instructions(Prompt.TOOL_SYSTEM)
            .input(Prompt.user(input, now, history))
            .addTool(ReminderTool.definition())
            .toolChoice(ToolChoiceOptions.AUTO)
            .build()

    companion object {
        const val TAG = "Daodian/ToolCall"

        /** 完整 Response → 结果。流式和非流式共用，判定只有这一份 */
        private fun resultOfResponse(response: Response, now: ZonedDateTime): ParseResult {
            val call = response.output().firstNotNullOfOrNull { item ->
                item.functionCall().orElse(null)?.takeIf { it.name() == ReminderTool.NAME }
            }
            if (call != null) {
                Log.i(TAG, "模型调了工具: ${call.name()} args=${call.arguments().take(300)}")
                val plan = ReminderTool.toPlan(call.arguments())
                    ?: return ParseResult.Failed("工具参数解析不了：${call.arguments().take(200)}")
                return PlanValidator.validate(plan, now)
            }

            // 没调工具 —— 模型在反问，或者跑偏了。都当澄清处理
            val text = buildString {
                response.output().forEach { item ->
                    item.message().ifPresent { msg ->
                        msg.content().forEach { part -> part.outputText().ifPresent { append(it.text()) } }
                    }
                }
            }.trim()
            Log.i(TAG, "模型没调工具，回了文字: ${text.take(200)}")
            return if (text.isBlank()) {
                ParseResult.Failed("模型既没调工具也没返回文字")
            } else {
                ParseResult.NeedsClarification(text, text)
            }
        }
    }
}
