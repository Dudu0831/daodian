package com.abc.daodian.runtime.model

import com.abc.daodian.runtime.context.Preamble
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.tool.Tool
import com.openai.core.JsonValue
import com.openai.core.http.StreamResponse
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import kotlinx.coroutines.channels.SendChannel

/**
 * [LlmClient] 的 Chat Completions 实现（`POST {baseUrl}/chat/completions`）。只认这一种的网关不少，配置页里选（[ProviderProfile.api]）。
 * 流式、回退、取消在 [StreamingClient]，和 [ResponsesClient] 是同一套。
 *
 * 和 Responses 不一样的地方：
 *  - system 是第一条 `system` 消息，垫的背景照旧是 user 角色（[Preamble.MARK]）
 *  - 工具调用挂在 assistant 消息的 `tool_calls` 上：同一轮里紧挨着的几个调用并成一条，前面有话就是这条的 content
 *  - 流里工具调用按 `index` 一截截来，没有「这个调用收全了」的事件，收到 `finish_reason` 才算收全
 *  - 思考：官方协议里没有思考流；DeepSeek 一类在 delta 上多给一个 `reasoning_content`，有就接
 */
internal class ChatCompletionsClient(profile: ProviderProfile) :
    StreamingClient<ChatCompletionCreateParams, ChatCompletionChunk>(profile) {

    override fun openStream(params: ChatCompletionCreateParams): StreamResponse<ChatCompletionChunk> =
        client.chat().completions().createStreaming(params)

    override fun once(params: ChatCompletionCreateParams): StepOutput = outputOf(client.chat().completions().create(params))

    override fun accumulator(): Accumulator<ChatCompletionChunk> = Chunks()

    override fun params(request: LlmRequest): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(profile.model)
            .addSystemMessage(request.system)
        request.background?.let { builder.addUserMessage("${Preamble.MARK}\n$it") }
        wire(builder, request.input)
        // 关的时候不带这个字段：只认 chat 的网关里不少不认 effort=none，带了直接 400。代价是推理模型关了照样想
        if (profile.thinking) builder.reasoningEffort(ReasoningEffort.MEDIUM)
        if (request.tools.isNotEmpty()) {
            request.tools.forEach { builder.addTool(wire(it)) }
            builder.toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO)
        }
        return builder.build()
    }

    private class Partial(var id: String? = null, var name: String? = null, val args: StringBuilder = StringBuilder()) {
        /** id 和名字都到了才告诉界面「在办」；之前来的参数先攒着 */
        var started = false
    }

    private class Chunks : Accumulator<ChatCompletionChunk> {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Long, Partial>()
        var finished = false
        override var failure: String? = null

        override suspend fun consume(event: ChatCompletionChunk, out: SendChannel<LlmEvent>) {
            event.choices().firstOrNull()?.let { choice ->
                val delta = choice.delta()
                delta._additionalProperties()["reasoning_content"]?.asString()?.orElse(null)?.takeIf { it.isNotEmpty() }?.let {
                    reasoning.append(it)
                    out.send(LlmEvent.Reasoning(it))
                }
                delta.content().orElse(null)?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    out.send(LlmEvent.Text(it))
                }
                delta.toolCalls().orElse(null)?.forEach { piece ->
                    // 只有一个调用时有的网关不给 index
                    val index = piece._index().asKnown().orElse(calls.keys.lastOrNull() ?: 0L)
                    val call = calls.getOrPut(index) { Partial() }
                    piece.id().orElse(null)?.takeIf { it.isNotEmpty() }?.let { call.id = it }
                    piece.function().orElse(null)?.let { f ->
                        f.name().orElse(null)?.takeIf { it.isNotEmpty() }?.let { call.name = it }
                        val more = f.arguments().orElse(null).orEmpty()
                        call.args.append(more)
                        val id = call.id
                        val name = call.name
                        if (!call.started && id != null && name != null) {
                            call.started = true
                            out.send(LlmEvent.ToolStarted(id, name))
                            if (call.args.isNotEmpty()) out.send(LlmEvent.ToolArgs(id, call.args.toString()))
                        } else if (call.started && more.isNotEmpty()) {
                            out.send(LlmEvent.ToolArgs(id!!, more))
                        }
                    }
                }
                if (choice.finishReason().isPresent) finished = true
            }
        }

        override fun output(interrupted: Boolean): StepOutput? {
            val complete = !interrupted || finished
            val toolCalls = if (complete) {
                calls.values.mapIndexedNotNull { i, c ->
                    c.name?.let { Item.ToolCall(c.id ?: "call_$i", it, c.args.toString().ifBlank { "{}" }) }
                }
            } else emptyList()
            if (text.isBlank() && toolCalls.isEmpty()) return null
            return StepOutput(text.toString().trim(), toolCalls, reasoning.toString())
        }
    }

    companion object {

        private fun outputOf(completion: ChatCompletion): StepOutput {
            val message = completion.choices().firstOrNull()?.message()
                ?: throw LlmException("网关回了，但里面没有 choices")
            val calls = message.toolCalls().orElse(emptyList()).mapNotNull { call ->
                call.function().orElse(null)?.let { Item.ToolCall(it.id(), it.function().name(), it.function().arguments()) }
            }
            val reasoning = message._additionalProperties()["reasoning_content"]?.asString()?.orElse(null).orEmpty()
            return StepOutput(message.content().orElse(null).orEmpty().trim(), calls, reasoning)
        }

        /**
         * 历史一项项摆成消息。Chat 没有单独的「调用」条目：assistant 的话和紧跟着的调用并成一条 assistant 消息，
         * 每个结果各一条 tool 消息
         */
        private fun wire(builder: ChatCompletionCreateParams.Builder, items: List<Item>) {
            var said: String? = null
            val pending = mutableListOf<Item.ToolCall>()
            fun flush() {
                if (said == null && pending.isEmpty()) return
                val m = ChatCompletionAssistantMessageParam.builder()
                said?.let { m.content(it) }
                pending.forEach { call ->
                    m.addToolCall(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id(call.callId)
                            .function(
                                ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(call.name)
                                    .arguments(call.arguments)
                                    .build()
                            )
                            .build()
                    )
                }
                builder.addMessage(m.build())
                said = null
                pending.clear()
            }
            items.forEach { item ->
                when (item) {
                    is Item.UserMessage -> {
                        flush()
                        if (item.images.isEmpty()) builder.addUserMessage(item.content()) else pictured(builder, item)
                    }
                    is Item.AssistantMessage -> {
                        flush()
                        said = item.text
                    }
                    is Item.ToolCall -> pending += item
                    is Item.ToolResult -> {
                        flush()
                        builder.addMessage(
                            ChatCompletionToolMessageParam.builder().toolCallId(item.callId).content(item.output).build()
                        )
                    }
                }
            }
            flush()
        }

        /** 带图的一句：文字在前、图在后 */
        private fun pictured(builder: ChatCompletionCreateParams.Builder, item: Item.UserMessage) {
            val urls = item.images.mapNotNull(::dataUrl)
            val parts = mutableListOf(
                ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder().text(pictureText(item, urls)).build())
            )
            urls.forEach {
                parts += ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                        .imageUrl(
                            ChatCompletionContentPartImage.ImageUrl.builder()
                                .url(it)
                                .detail(ChatCompletionContentPartImage.ImageUrl.Detail.AUTO)
                                .build()
                        )
                        .build()
                )
            }
            builder.addUserMessageOfArrayOfContentParts(parts)
        }

        private fun wire(tool: Tool): ChatCompletionFunctionTool =
            ChatCompletionFunctionTool.builder()
                .function(
                    FunctionDefinition.builder()
                        .name(tool.name)
                        .description(tool.description)
                        .strict(tool.strict)
                        .parameters(
                            FunctionParameters.builder()
                                .apply { tool.parameters.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                                .build()
                        )
                        .build()
                )
                .build()
    }
}
