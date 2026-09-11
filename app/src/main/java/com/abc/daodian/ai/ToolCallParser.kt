package com.abc.daodian.ai

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.StreamResponse
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ToolChoiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.SendChannel
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference
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

    override suspend fun parse(input: String, now: ZonedDateTime, history: List<ChatTurn>): ParseResult {
        if (!profile.isConfigured) {
            return ParseResult.Failed("还没配置供应商：${profile.redacted()}")
        }
        val response = try {
            detached { client.responses().create(params(input, now, history)) }
        } catch (c: CancellationException) {
            throw c                                     // 用户点了「停」，不是失败
        } catch (t: Throwable) {
            Log.e(TAG, "调用失败", t)
            return ParseResult.Failed("${t.javaClass.simpleName}: ${t.message}", t)
        }
        return resultOfResponse(response, now)
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

        val out: SendChannel<ParseEvent> = this
        val acc = Accumulator()
        var streamed = false
        // 连上之后的那条流。点「停」时由 onCancel 直接 close，阻塞在 hasNext() 里的迭代随之抛出醒来
        val live = AtomicReference<StreamResponse<ResponseStreamEvent>?>()

        val result: ParseResult? = try {
            detached(onCancel = { live.get()?.let { runCatching { it.close() } } }) {
                val stream = client.responses().createStreaming(params(input, now, history))
                live.set(stream)
                try {
                    ensureActive()                      // 等响应头时就被喊停了：一连上就关
                    val events = stream.stream().iterator()
                    while (events.hasNext()) {
                        ensureActive()
                        streamed = true
                        out.emitEventsOf(events.next(), acc)
                    }
                } finally {
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

        /** 阻塞 HTTP 活的去处：不挂在任何调用方下面，调用方取消了它也不会连坐着把调用方拖住 */
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 阻塞的 HTTP 调用放到不跟调用方一起取消的线程上跑，调用方只挂起等结果。
         *
         * 为什么绕这一下：SDK 的同步调用在响应头回来之前，没有任何能从外面掐断的句柄；
         * 协程取消又打断不了阻塞 IO。直接包在 withContext 里，点「停」就得陪着它干等 ——
         * 流式要等到首个 token，回退的一次性请求要等整段回完。异步版 SDK 也救不了：
         * `AsyncStreamResponse.close()` 只是等 future 落地后再关，重试链的 thenCompose 也不往下传取消。
         * （旧写法挂 `invokeOnCompletion` 也不行 —— 它在 Job **完成**时才触发，而 Job 正卡在阻塞调用里完成不了。）
         *
         * 现在取消时调用方立刻返回。[onCancel] 负责掐掉已经连上的流；还没连上的，
         * 由 [block] 连上后自己 ensureActive 发现、随手关掉。
         */
        private suspend fun <T> detached(onCancel: () -> Unit = {}, block: suspend CoroutineScope.() -> T): T {
            val work = io.async(block = block)
            try {
                return work.await()
            } catch (c: CancellationException) {
                // 先标取消、再 onCancel：block 那边是先登记流、再查取消，两边交叉，谁晚到谁关，漏不掉
                work.cancel()
                onCancel()
                throw c
            }
        }

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
