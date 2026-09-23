package com.abc.daodian

import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.model.LlmClient
import com.abc.daodian.agent.model.LlmEvent
import com.abc.daodian.agent.model.LlmRequest
import com.abc.daodian.agent.model.ResponsesClient
import com.abc.daodian.agent.model.provider.ProviderProfile
import com.abc.daodian.agent.prompt.BasePrompt
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.reminder.tools.ReminderPrompt
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Properties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 打真网关：验证「工具结果回传 → 模型接着说」和「历史里的工具调用原样重放」这两件事网关认不认。
 * 要花钱、要联网，默认跳过。跑法（在项目根目录）：
 *
 *     DAODIAN_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveGatewayTest*' -i
 *
 * 配置读根目录的 secrets.properties；想换供应商不改文件，用环境变量覆盖：
 * `DAODIAN_BASE_URL` / `DAODIAN_KEY` / `DAODIAN_MODEL`。落库是假的，不碰手机。
 */
class LiveGatewayTest {

    private lateinit var profile: ProviderProfile

    @Before
    fun setUp() {
        assumeTrue("没设 DAODIAN_LIVE=1，跳过", System.getenv("DAODIAN_LIVE") == "1")
        val file = File("../secrets.properties").takeIf { it.exists() } ?: File("secrets.properties")
        val p = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
        fun setting(env: String, key: String) = (System.getenv(env) ?: p.getProperty(key))?.trim().orEmpty()
        profile = ProviderProfile(
            baseUrl = setting("DAODIAN_BASE_URL", "LLM_BASE_URL").removeSuffix("/"),
            apiKey = setting("DAODIAN_KEY", "LLM_API_KEY"),
            model = setting("DAODIAN_MODEL", "LLM_MODEL")
        )
        assumeTrue("供应商没配全（secrets.properties 或环境变量）", profile.isConfigured)
    }

    /** 数一数这一轮调了几次模型 */
    private class Counting(private val inner: LlmClient) : LlmClient {
        var calls = 0
        override fun step(request: LlmRequest): Flow<LlmEvent> {
            calls++
            return inner.step(request)
        }
    }

    @Test
    fun `tool result round trip and replayed history`() = runBlocking {
        val committed = mutableListOf<String>()
        val llm = Counting(ResponsesClient(profile))
        val tools = ToolRegistry(listOf(CreateReminderTool { plan, _ ->
            committed += "${plan.title} @ ${plan.firstTriggerAt}"
            1001L
        }))
        val loop = AgentLoop(llm, tools, BasePrompt.SYSTEM + "\n\n" + ReminderPrompt.RULES)
        val session = Session()
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))

        // 第一轮：要走完「调工具 → 结果回传 → 模型收尾」
        val first = loop.run(session, "明天下午三点提醒我交房租", now).onEach(::log).toList()
        println("第一轮调模型 ${llm.calls} 次，落库：$committed")
        val end1 = first.last()
        assertTrue("第一轮没正常结束：$end1", end1 is AgentEvent.Finished)
        assertEquals("应该恰好建一条", 1, committed.size)
        assertTrue("工具结果没回传给模型（只调了 ${llm.calls} 次）", llm.calls >= 2)

        // 第二轮：历史里带着上一轮的 function_call / output，网关得认
        llm.calls = 0
        val second = loop.run(session, "我刚才让你提醒我什么？", now.plusMinutes(1)).onEach(::log).toList()
        val end2 = second.last()
        assertTrue("第二轮没正常结束：$end2", end2 is AgentEvent.Finished)
        val reply = (end2 as AgentEvent.Finished).turn.items.filterIsInstance<Item.AssistantMessage>().joinToString { it.text }
        println("第二轮回答：$reply")
        assertTrue("模型没从历史里认出房租：$reply", "房租" in reply)
        assertEquals("第二轮不该再建", 1, committed.size)
    }

    private fun log(e: AgentEvent) {
        when (e) {
            is AgentEvent.Model -> when (val m = e.event) {
                is LlmEvent.ToolStarted -> println("  [${e.step}] 调工具 ${m.name}")
                is LlmEvent.FellBack -> println("  [${e.step}] 流式没走通，回退一次性")
                is LlmEvent.Done -> println("  [${e.step}] 完成：text=${m.output.text.take(80)} calls=${m.output.toolCalls}")
                else -> Unit
            }
            is AgentEvent.ToolFinished -> println("  工具结果：${e.outcome.output}")
            else -> println("  $e")
        }
    }
}
