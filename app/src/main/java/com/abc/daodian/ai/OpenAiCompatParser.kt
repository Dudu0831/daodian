package com.abc.daodian.ai

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig
import com.openai.models.responses.ResponseTextConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * 用 openai-java 官方 SDK 实现。见 DESIGN.md 决策 3.1
 *
 * 两种接口风格都支持，由 [ProviderProfile.apiStyle] 决定：
 *  - CHAT_COMPLETIONS: POST {baseUrl}/chat/completions —— 事实标准
 *  - RESPONSES:        POST {baseUrl}/responses
 */
class OpenAiCompatParser(private val profile: ProviderProfile) : ReminderParser {

    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .apiKey(profile.apiKey)
            .baseUrl(profile.baseUrl)
            .build()
    }

    override suspend fun parse(input: String, now: ZonedDateTime): ParseResult =
        withContext(Dispatchers.IO) {
            if (!profile.isConfigured) {
                return@withContext ParseResult.Failed("还没配置供应商：${profile.redacted()}")
            }
            Log.i(TAG, "解析中… ${profile.redacted()}")

            val raw = try {
                when (profile.apiStyle) {
                    ApiStyle.RESPONSES -> callResponses(input, now)
                    ApiStyle.CHAT_COMPLETIONS -> callChatCompletions(input, now)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "调用失败", t)
                return@withContext ParseResult.Failed("${t.javaClass.simpleName}: ${t.message}", t)
            }

            Log.i(TAG, "原始返回: ${raw.take(400)}")

            val plan = JsonExtract.toPlan(raw)
                ?: return@withContext ParseResult.Failed("返回内容里找不到可用的 JSON：${raw.take(200)}")

            PlanValidator.validate(plan, now)
        }

    private fun callChatCompletions(input: String, now: ZonedDateTime): String {
        val b = ChatCompletionCreateParams.builder()
            .model(profile.model)
            .addSystemMessage(Prompt.SYSTEM)
            .addUserMessage(Prompt.user(input, now))
        // 见 DESIGN.md §6.2 的三档降级。PROMPT_ONLY 不传这个字段 ——
        // 有些供应商见到不认识的参数会直接 400。
        if (profile.jsonMode != JsonMode.PROMPT_ONLY) {
            b.responseFormat(ResponseFormatJsonObject.builder().build())
        }
        val params = b.build()
        val completion = client.chat().completions().create(params)
        return completion.choices()
            .firstNotNullOfOrNull { it.message().content().orElse(null) }
            .orEmpty()
    }

    private fun callResponses(input: String, now: ZonedDateTime): String {
        val b = ResponseCreateParams.builder()
            .model(profile.model)
            .instructions(Prompt.SYSTEM)
            .input(Prompt.user(input, now))
        when (profile.jsonMode) {
            JsonMode.STRICT_SCHEMA -> b.text(
                ResponseTextConfig.builder().format(
                    ResponseFormatTextJsonSchemaConfig.builder()
                        .name(PlanSchema.NAME)
                        .strict(true)
                        .schema(
                            ResponseFormatTextJsonSchemaConfig.Schema.builder()
                                .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
                                .putAdditionalProperty("properties", com.openai.core.JsonValue.from(PlanSchema.PROPERTIES))
                                .putAdditionalProperty("required", com.openai.core.JsonValue.from(PlanSchema.REQUIRED))
                                .putAdditionalProperty("additionalProperties", com.openai.core.JsonValue.from(false))
                                .build()
                        )
                        .build()
                ).build()
            )
            JsonMode.JSON_OBJECT -> b.text(
                ResponseTextConfig.builder()
                    .format(ResponseFormatJsonObject.builder().build())
                    .build()
            )
            // 有些供应商见到不认识的参数会直接 400，这档什么都不传
            JsonMode.PROMPT_ONLY -> Unit
        }
        val params = b.build()
        val response = client.responses().create(params)
        return extractText(response)
    }

    /** Responses API 的输出要往下走两层：output → message → content → outputText */
    private fun extractText(response: com.openai.models.responses.Response): String =
        buildString {
            response.output().forEach { item ->
                item.message().ifPresent { msg ->
                    msg.content().forEach { part ->
                        part.outputText().ifPresent { append(it.text()) }
                    }
                }
            }
        }

    companion object { const val TAG = "Daodian/Parser" }
}
