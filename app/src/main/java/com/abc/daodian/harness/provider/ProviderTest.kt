package com.abc.daodian.harness.provider

import com.abc.daodian.harness.Item
import com.abc.daodian.harness.builtin.reminder.CreateReminderTool
import com.abc.daodian.harness.llm.LlmException
import com.abc.daodian.harness.llm.LlmRequest
import com.abc.daodian.harness.llm.ResponsesClient
import com.abc.daodian.harness.prompt.HarnessPrompt
import kotlinx.coroutines.flow.collect
import java.time.ZonedDateTime

/** 「测一下」的结果 */
sealed interface PingResult {
    data class Ok(val millis: Long) : PingResult
    data class Failed(val why: String, val raw: String) : PingResult
}

/**
 * 测一下这份配置能不能用。
 *
 * 走的就是**真正那条路**：同一个 [ResponsesClient]、同一份 system、同样挂着工具，
 * 只是换成一句最短的话、只调一步。不另外拿 `/models` 之类的接口试探 ——
 * 第三方网关不一定实现它，测通了也不代表正式调用能通，那种「绿灯」比没有还坏。
 *
 * 测的是**传进来的**配置，不是已保存的：配置页里改完能先测再存。
 */
object ProviderTest {

    /** 只拿来让请求带上工具定义；一步调用不会执行它 */
    private val probeTool = CreateReminderTool { _, _ -> error("「测一下」不该执行工具") }

    suspend fun run(profile: ProviderProfile): PingResult {
        if (!profile.isConfigured) {
            return PingResult.Failed("三格还没填全", profile.redacted())
        }
        val request = LlmRequest(
            system = HarnessPrompt.SYSTEM,
            input = listOf(Item.UserMessage("你好", ZonedDateTime.now())),
            tools = listOf(probeTool)
        )
        val started = System.currentTimeMillis()
        return try {
            ResponsesClient(profile).step(request).collect()
            ApiHealth.recordOk()
            PingResult.Ok(System.currentTimeMillis() - started)
        } catch (e: LlmException) {
            ApiHealth.recordDown(e)
            PingResult.Failed(ApiHealth.humanize(e.message.orEmpty(), e), e.message.orEmpty())
        }
    }
}
