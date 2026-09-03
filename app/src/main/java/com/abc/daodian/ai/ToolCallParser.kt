package com.abc.daodian.ai

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ToolChoiceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * 走工具调用，而不是求模型输出 JSON。见 ReminderTool 的说明。
 *
 * 模型有两条出路：
 *  - 信息够 → 调 create_reminder 工具，参数 schema 由服务端强制
 *  - 信息不够 → 不调工具，用文字反问 → 变成 NeedsClarification
 */
class ToolCallParser(private val profile: ProviderProfile) : ReminderParser {

    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .apiKey(profile.apiKey)
            .baseUrl(profile.baseUrl)
            .build()
    }

    override suspend fun parse(input: String, now: ZonedDateTime, history: List<ChatTurn>): ParseResult =
        withContext(Dispatchers.IO) {
            if (!profile.isConfigured) {
                return@withContext ParseResult.Failed("还没配置供应商：${profile.redacted()}")
            }

            val response = try {
                client.responses().create(
                    ResponseCreateParams.builder()
                        .model(profile.model)
                        .instructions(Prompt.TOOL_SYSTEM)
                        .input(Prompt.user(input, now, history))
                        .addTool(ReminderTool.definition())
                        .toolChoice(ToolChoiceOptions.AUTO)
                        .build()
                )
            } catch (t: Throwable) {
                Log.e(TAG, "调用失败", t)
                return@withContext ParseResult.Failed("${t.javaClass.simpleName}: ${t.message}", t)
            }

            val call = findToolCall(response)
            if (call != null) {
                Log.i(TAG, "模型调了工具: ${call.name()} args=${call.arguments().take(300)}")
                val plan = ReminderTool.toPlan(call.arguments())
                    ?: return@withContext ParseResult.Failed("工具参数解析不了：${call.arguments().take(200)}")
                return@withContext PlanValidator.validate(plan, now)
            }

            // 没调工具 —— 模型在反问，或者跑偏了。都当澄清处理
            val text = extractText(response).trim()
            Log.i(TAG, "模型没调工具，回了文字: ${text.take(200)}")
            if (text.isBlank()) {
                ParseResult.Failed("模型既没调工具也没返回文字")
            } else {
                ParseResult.NeedsClarification(text, text)
            }
        }

    private fun findToolCall(response: Response) =
        response.output().firstNotNullOfOrNull { item ->
            item.functionCall().orElse(null)?.takeIf { it.name() == ReminderTool.NAME }
        }

    private fun extractText(response: Response): String =
        buildString {
            response.output().forEach { item ->
                item.message().ifPresent { msg ->
                    msg.content().forEach { part ->
                        part.outputText().ifPresent { append(it.text()) }
                    }
                }
            }
        }

    companion object { const val TAG = "Daodian/ToolCall" }
}
