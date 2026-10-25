package com.abc.daodian.runtime.model

import com.abc.daodian.runtime.context.Preamble
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.tool.Tool
import com.openai.core.JsonValue
import com.openai.core.http.StreamResponse
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
import kotlinx.coroutines.channels.SendChannel

/**
 * [LlmClient] 的 Responses API 实现（`POST {baseUrl}/responses`）。流式、回退、取消在 [StreamingClient]，这里只管这家协议长什么样。
 *
 * 只在 runtime 里用（`internal`）：app 跑 agent 走 `AgentRuntime`（按配置缓存一个它），测一下配置走 `AgentRuntime.test`。
 */
internal class ResponsesClient(profile: ProviderProfile) :
    StreamingClient<ResponseCreateParams, ResponseStreamEvent>(profile) {

    override fun openStream(params: ResponseCreateParams): StreamResponse<ResponseStreamEvent> =
        client.responses().createStreaming(params)

    override fun once(params: ResponseCreateParams): StepOutput = outputOf(client.responses().create(params).output())

    override fun accumulator(): Accumulator<ResponseStreamEvent> = Events()

    override fun params(request: LlmRequest): ResponseCreateParams {
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

    /** 完整的 Response 优先；网关不发 completed 时退而用逐条收全的 output item（收全了的才在里面，断在半路也照用） */
    private class Events : Accumulator<ResponseStreamEvent> {
        val reasoning = StringBuilder()
        val doneItems = sortedMapOf<Long, ResponseOutputItem>()
        /** 流里参数增量只带 item id，要换成 call id 才对得上 */
        val callIdOfItem = HashMap<String, String>()
        var completed: Response? = null
        override var failure: String? = null

        override suspend fun consume(event: ResponseStreamEvent, out: SendChannel<LlmEvent>) {
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

        override fun output(interrupted: Boolean): StepOutput? {
            val items = completed?.output() ?: doneItems.values.toList()
            if (items.isEmpty()) return null
            val out = outputOf(items)
            return if (out.reasoning.isEmpty() && reasoning.isNotEmpty()) out.copy(reasoning = reasoning.toString()) else out
        }
    }

    companion object {

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

        /** 带图的一句：文字在前、图在后 */
        private fun pictured(item: Item.UserMessage): ResponseInputItem {
            val urls = item.images.mapNotNull(::dataUrl)
            val parts = mutableListOf(
                ResponseInputContent.ofInputText(ResponseInputText.builder().text(pictureText(item, urls)).build())
            )
            urls.forEach {
                parts += ResponseInputContent.ofInputImage(
                    ResponseInputImage.builder().imageUrl(it).detail(ResponseInputImage.Detail.AUTO).build()
                )
            }
            return ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().role(EasyInputMessage.Role.USER).contentOfResponseInputMessageContentList(parts).build()
            )
        }

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
