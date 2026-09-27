package com.abc.daodian

import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.Turn
import com.abc.daodian.agent.engine.context.LastTurns
import com.abc.daodian.agent.engine.context.Preamble
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.memory.EditMemoryTool
import com.abc.daodian.agent.memory.FakeMemoryBook
import com.abc.daodian.agent.memory.tidy.SaveTidyTool
import com.abc.daodian.agent.memory.tidy.TidyPrompt
import com.abc.daodian.agent.memory.tidy.TidyText
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * 垫在前面的记忆（§6.9）：网关认不认开头那条「app 附上的背景」、模型拿不拿它听懂「晚点」；
     * 他说「记住…」时调不调 edit_memory。
     */
    @Test
    fun `background memory is used and edit_memory is called when asked`() = runBlocking {
        val committed = mutableListOf<String>()
        val book = FakeMemoryBook("他说的「晚点」一般指晚上 9 点")
        val tools = ToolRegistry(listOf(
            CreateReminderTool { plan, _ -> committed += plan.firstTriggerAt; 1001L },
            EditMemoryTool(book)
        ))
        val loop = AgentLoop(ResponsesClient(profile), tools, BasePrompt.SYSTEM + "\n\n" + ReminderPrompt.RULES)
        val session = Session()
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).withHour(15).withMinute(0)
        val background = { _: Session -> Preamble("你记得的关于他的事（m 编号 · 记下或改过的日子）：\n- m1 · 9月20日 · 他说的「晚点」一般指晚上 9 点") }

        val first = loop.run(session, "晚点提醒我给妈妈打电话", now, background = background).onEach(::log).toList()
        assertTrue("第一轮没正常结束：${first.last()}", first.last() is AgentEvent.Finished)
        println("建的：$committed")
        assertTrue("没按记忆排到 21:00：$committed", committed.singleOrNull()?.contains("T21:00") == true)

        val second = loop.run(session, "记住：我每周二晚上健身", now.plusMinutes(1), background = background).onEach(::log).toList()
        assertTrue("第二轮没正常结束：${second.last()}", second.last() is AgentEvent.Finished)
        println("记忆：${book.list.map { it.text }}")
        assertTrue("没记下健身：${book.list}", book.list.any { "健身" in it.text })
    }

    /** 整理员（§6.9）：看一段对话，交上记忆和摘要；账目、一次性的事不进记忆，摘要不写「已经办好」 */
    @Test
    fun `tidy extracts memories and writes a summary`() = runBlocking {
        val book = FakeMemoryBook("他在备考 CPA")
        val at = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1).withHour(21).withMinute(3)
        val turns = listOf(
            Turn(1, listOf(
                Item.UserMessage("我一般九点到公司，晚上七点下班。明早八点提醒我带伞", at),
                Item.ToolCall("c1", "create_reminder", "{\"title\":\"带伞\"}"),
                Item.ToolResult("c1", "已建好：明天 08:00 带伞", ok = true),
                Item.AssistantMessage("好了，明天 08:00 提醒你带伞。")
            )),
            Turn(2, listOf(
                Item.UserMessage("考试改到 12 月了。以后说老地方就是公司楼下那家瑞幸", at.plusMinutes(2)),
                Item.AssistantMessage("知道了。")
            )),
            Turn(3, listOf(
                Item.UserMessage("刚才午饭 36.5 是和同事吃的", at.plusMinutes(5)),
                Item.ToolCall("c2", "update_expenses", "{}"),
                Item.ToolResult("c2", "改好了 1 笔：午饭 36.50 → 餐饮/堂食", ok = true),
                Item.AssistantMessage("改好了。")
            ))
        )
        val loop = AgentLoop(ResponsesClient(profile), ToolRegistry(listOf(SaveTidyTool(book, compact = true))), TidyPrompt.SYSTEM,
            context = LastTurns(1), maxSteps = 3)
        var saved: SaveTidyTool.Saved? = null
        val input = TidyText.input(book.list.toList(), null, turns, compact = true, stale = emptySet())
        println(input)
        loop.run(Session(), input, ZonedDateTime.now()).onEach(::log).collect { e ->
            if (e is AgentEvent.ToolFinished) (e.outcome.payload as? SaveTidyTool.Saved)?.let { saved = it }
        }
        val s = saved
        assertNotNull("没交作业", s)
        println("记忆：${book.list.map { "m${it.id} ${it.text}" }}\n摘要：${s!!.summary}")
        assertTrue("没记下作息或老地方", book.list.any { "瑞幸" in it.text || "九点" in it.text || "9" in it.text })
        assertTrue("考试改期没更新到 m1", book.list.none { it.text == "他在备考 CPA" })
        assertFalse("账进了记忆", book.list.any { "36" in it.text })
        assertNotNull(s.summary)
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
