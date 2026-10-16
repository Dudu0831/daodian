package com.abc.daodian.runtime.model

import com.abc.daodian.runtime.context.Preamble
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.tool.Tool
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.core.http.StreamResponse
import com.openai.errors.OpenAIServiceException
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ToolChoiceOptions
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/**
 * [LlmClient] 的 Responses API 实现。流式为主，流没跑起来就退回一次性请求 ——
 * 第三方兼容网关不一定实现 SSE。取消和回退的规矩见 DESIGN.md §6.2。
 *
 * 只在 runtime 里用（`internal`）：app 跑 agent 走 `AgentRuntime`（按配置缓存一个它），测一下配置走 [ProviderTest]。
 */
internal class ResponsesClient(private val profile: ProviderProfile) : LlmClient {

    override val model: String get() = profile.model

    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .apiKey(profile.apiKey)
            .baseUrl(profile.baseUrl)
            .build()
    }

    /**
     * 判定「流没跑起来」的两种情形：`createStreaming` 直接抛，或流开了一个事件都没有。
     * 流开到一半断掉不回退 —— 工具参数可能已经收全，重来一遍等于让模型再决定一次；
     * 这时按手上已经收全的条目判，一条都没收全才算失败。
     */
    override fun step(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        if (!profile.isConfigured) throw LlmException("还没配置供应商：${profile.redacted()}")
        // 带图的那几句要读文件、转 base64，别在调用方的线程上干
        val params = withContext(Dispatchers.IO) { params(request) }
        val withImages = request.input.any { it is Item.UserMessage && it.images.isNotEmpty() }
        /** 带着图、又是被网关退回来的：单独一种错，界面上另报一句（[ImagesRejectedException]） */
        fun failure(message: String, cause: Throwable?, rejected: Boolean): LlmException =
            if (withImages && rejected) ImagesRejectedException(message, cause) else LlmException(message, cause)
        val acc = Accumulator()
        var streamed = false
        /** 流式没跑起来的原因。回退也失败时两个原因一起报，只报后一个会误导 */
        var streamError: String? = null
        val live = AtomicReference<StreamResponse<ResponseStreamEvent>?>()

        val output: StepOutput? = try {
            detached(onCancel = { live.get()?.let { runCatching { it.close() } } }) {
                val stream = client.responses().createStreaming(params)
                live.set(stream)
                try {
                    ensureActive()
                    val events = stream.stream().iterator()
                    while (events.hasNext()) {
                        ensureActive()
                        streamed = true
                        acc.consume(events.next(), this@channelFlow)
                    }
                } finally {
                    runCatching { stream.close() }
                }
            }
            if (streamed) {
                acc.output() ?: throw failure(
                    acc.failure ?: "流结束了，但没有收到任何完整的输出", null,
                    rejected = acc.failure?.let(::mentionsImages) == true
                )
            } else null
        } catch (c: CancellationException) {
            throw c
        } catch (e: LlmException) {
            throw e
        } catch (t: Throwable) {
            if (streamed) acc.output() ?: throw LlmException("流中断：${t.javaClass.simpleName}: ${t.message}", t)
            else null.also { streamError = "${t.javaClass.simpleName}: ${t.message}" }
        }

        if (output != null) {
            send(LlmEvent.Done(output))
        } else {
            send(LlmEvent.FellBack)
            val response = try {
                detached { client.responses().create(params) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val first = streamError ?: "流开了但一个事件都没有"
                throw failure("流式：$first；回退一次性：${t.javaClass.simpleName}: ${t.message}", t, rejected = isRejection(t))
            }
            send(LlmEvent.Done(outputOf(response.output())))
        }
    }

    private fun params(request: LlmRequest): ResponseCreateParams {
        val builder = ResponseCreateParams.builder()
            .model(profile.model)
            .instructions(request.system)
            .inputOfResponse(listOfNotNull(request.background?.let(::background)) + request.input.map(::wire))
            .reasoning(reasoning())
        if (request.tools.isNotEmpty()) {
            request.tools.forEach { builder.addTool(wire(it)) }
            builder.toolChoice(ToolChoiceOptions.AUTO)
        }
        return builder.build()
    }

    /** 见 [ProviderProfile.thinking] */
    private fun reasoning(): Reasoning =
        if (profile.thinking) {
            Reasoning.builder().effort(ReasoningEffort.MEDIUM).summary(Reasoning.Summary.AUTO).build()
        } else {
            Reasoning.builder().effort(ReasoningEffort.NONE).build()
        }

    /** 一条流收下来攒的东西。完整的 Response 优先；网关不发 completed 时退而用逐条收全的 output item */
    private class Accumulator {
        val reasoning = StringBuilder()
        val doneItems = sortedMapOf<Long, ResponseOutputItem>()
        /** 流里参数增量只带 item id，要换成 call id 才对得上 */
        val callIdOfItem = HashMap<String, String>()
        var completed: Response? = null
        var failure: String? = null

        suspend fun consume(event: ResponseStreamEvent, out: SendChannel<LlmEvent>) {
            event.reasoningTextDelta().orElse(null)?.let {
                reasoning.append(it.delta())
                out.send(LlmEvent.Reasoning(it.delta()))
            }
            event.reasoningSummaryTextDelta().orElse(null)?.let {
                reasoning.append(it.delta())
                out.send(LlmEvent.Reasoning(it.delta()))
            }
            event.outputTextDelta().orElse(null)?.let { out.send(LlmEvent.Text(it.delta())) }
            event.outputItemAdded().orElse(null)?.item()?.functionCall()?.orElse(null)?.let { call ->
                call.id().orElse(null)?.let { callIdOfItem[it] = call.callId() }
                out.send(LlmEvent.ToolStarted(call.callId(), call.name()))
            }
            event.functionCallArgumentsDelta().orElse(null)?.let {
                out.send(LlmEvent.ToolArgs(callIdOfItem[it.itemId()] ?: it.itemId(), it.delta()))
            }
            event.outputItemDone().orElse(null)?.let { doneItems[it.outputIndex()] = it.item() }
            event.completed().orElse(null)?.let { completed = it.response() }
            event.incomplete().orElse(null)?.let { completed = it.response() }
            event.failed().orElse(null)?.let {
                failure = "模型侧失败：${it.response().error().orElse(null)?.message() ?: "未说明原因"}"
            }
            event.error().orElse(null)?.let { failure = "流错误：${it.message()}" }
        }

        /** null = 没有任何收全的输出 */
        fun output(): StepOutput? {
            val items = completed?.output() ?: doneItems.values.toList()
            if (items.isEmpty()) return null
            val out = outputOf(items)
            return if (out.reasoning.isEmpty() && reasoning.isNotEmpty()) out.copy(reasoning = reasoning.toString()) else out
        }
    }

    companion object {

        /** 阻塞 HTTP 活的去处：不挂在任何调用方下面，调用方取消了它也不会连坐着把调用方拖住 */
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 阻塞的 HTTP 调用放到不跟调用方一起取消的线程上跑，调用方只挂起等结果。
         *
         * 为什么绕这一下：SDK 的同步调用在响应头回来之前，没有任何能从外面掐断的句柄；
         * 协程取消又打断不了阻塞 IO。直接包在 withContext 里，点「停」就得陪着它干等 ——
         * 流式要等到首个 token，回退的一次性请求要等整段回完。异步版 SDK 也救不了：
         * `AsyncStreamResponse.close()` 只是等 future 落地后再关，重试链的 thenCompose 也不往下传取消。
         * （挂 `invokeOnCompletion` 也不行 —— 它在 Job **完成**时才触发，而 Job 正卡在阻塞调用里完成不了。）
         *
         * 取消时调用方立刻返回。[onCancel] 负责掐掉已经连上的流；还没连上的，
         * 由 [block] 连上后自己 ensureActive 发现、随手关掉。
         * 先标取消、再 onCancel：block 那边是先登记流、再查取消，两边交叉，谁晚到谁关，漏不掉。
         */
        private suspend fun <T> detached(onCancel: () -> Unit = {}, block: suspend CoroutineScope.() -> T): T {
            val work = io.async(block = block)
            try {
                return work.await()
            } catch (c: CancellationException) {
                work.cancel()
                onCancel()
                throw c
            }
        }

        private fun outputOf(items: List<ResponseOutputItem>): StepOutput {
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val calls = mutableListOf<Item.ToolCall>()
            items.forEach { item ->
                item.message().ifPresent { msg ->
                    msg.content().forEach { part -> part.outputText().ifPresent { text.append(it.text()) } }
                }
                item.functionCall().ifPresent { calls += Item.ToolCall(it.callId(), it.name(), it.arguments()) }
                item.reasoning().ifPresent { r -> r.summary().forEach { reasoning.append(it.text()) } }
            }
            return StepOutput(text.toString().trim(), calls, reasoning.toString())
        }

        private fun wire(item: Item): ResponseInputItem = when (item) {
            is Item.UserMessage ->
                if (item.images.isEmpty()) message(EasyInputMessage.Role.USER, item.content()) else pictured(item)
            is Item.AssistantMessage -> message(EasyInputMessage.Role.ASSISTANT, item.text)
            is Item.ToolCall -> ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                    .callId(item.callId)
                    .name(item.name)
                    .arguments(item.arguments)
                    .build()
            )
            is Item.ToolResult -> ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId(item.callId)
                    .output(item.output)
                    .build()
            )
        }

        /**
         * 垫在历史前面的记忆和摘要：用 user 角色，开头打标记说明不是用户说的（和 app 发起的一轮同一个办法）。
         * 不用 developer / system 角色 —— 第三方网关认不认没把握
         */
        private fun background(text: String): ResponseInputItem =
            message(EasyInputMessage.Role.USER, "${Preamble.MARK}\n$text")

        /**
         * 带图的一句：文字在前、图在后。图读成 base64 的 data URL 直接带在请求里，不依赖网关的文件接口。
         * 读不到的图（文件没了）换成一句话，别让模型对着一张不存在的图说话
         */
        private fun pictured(item: Item.UserMessage): ResponseInputItem {
            val urls = item.images.mapNotNull(::dataUrl)
            val missing = item.images.size - urls.size
            val text = item.content() + if (missing > 0) "\n（有 $missing 张图片读不到了）" else ""
            val parts = mutableListOf(ResponseInputContent.ofInputText(ResponseInputText.builder().text(text).build()))
            urls.forEach {
                parts += ResponseInputContent.ofInputImage(
                    ResponseInputImage.builder().imageUrl(it).detail(ResponseInputImage.Detail.AUTO).build()
                )
            }
            return ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().role(EasyInputMessage.Role.USER).contentOfResponseInputMessageContentList(parts).build()
            )
        }

        private fun dataUrl(path: String): String? = runCatching {
            val file = File(path)
            val mime = if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
            "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes())
        }.getOrNull()

        /** 网关把请求退了回来：格式不认（400 / 415 / 422）、太大（413）。鉴权、限流、地址不对不算，那是别的毛病 */
        private fun isRejection(t: Throwable): Boolean = t is OpenAIServiceException && t.statusCode() in REJECTED

        private val REJECTED = setOf(400, 413, 415, 422)

        /** 流里报的错没有状态码，只能看它说没说到图 */
        private fun mentionsImages(reason: String): Boolean = reason.lowercase().let { r -> IMAGE_WORDS.any { it in r } }

        private val IMAGE_WORDS = listOf("image", "vision", "multimodal", "multi-modal", "图")

        private fun message(role: EasyInputMessage.Role, text: String) =
            ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(text).build())

        private fun wire(tool: Tool): FunctionTool =
            FunctionTool.builder()
                .name(tool.name)
                .description(tool.description)
                .strict(tool.strict)
                .parameters(
                    FunctionTool.Parameters.builder()
                        .apply { tool.parameters.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                        .build()
                )
                .build()
    }
}
