package com.abc.daodian.runtime

import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.runtime.engine.AgentEvent
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Session
import com.abc.daodian.runtime.memory.Memory
import com.abc.daodian.runtime.memory.MemoryApplied
import com.abc.daodian.runtime.memory.MemoryBackend
import com.abc.daodian.runtime.memory.MemoryEdit
import com.abc.daodian.runtime.model.ApiHealth
import com.abc.daodian.runtime.model.ApiState
import com.abc.daodian.runtime.model.LlmClient
import com.abc.daodian.runtime.model.LlmEvent
import com.abc.daodian.runtime.model.LlmException
import com.abc.daodian.runtime.model.LlmRequest
import com.abc.daodian.runtime.model.ProviderProfile
import com.abc.daodian.runtime.model.StepOutput
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private val NOTE = Prompt("你是测试用的帮手。", "helper-v1", setOf("note_down"))
private val ASKING = Prompt("你是测试用的帮手，能问人。", "helper-v2", setOf("note_down", AskUserTool.NAME))

/** 测试用的 agent：只会记一句 */
private object Helper : AgentInfo {
    override val id = "helper"
    override val label = "帮手"
    override val owner = "test"
    override val wakes = "测试里"
    override val prompts = listOf(NOTE, ASKING)
}

class AgentRuntimeTest {

    private val now = ZonedDateTime.of(2026, 10, 15, 15, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val configured = ProviderProfile("https://example.test/v1", "key", "test-model")

    /** 照剧本一步步回：每调一次吐下一步。剧本里放异常就当场抛 */
    private class ScriptedLlm(vararg steps: Any) : LlmClient {
        private val left = ArrayDeque(steps.toList())
        var calls = 0

        override fun step(request: LlmRequest): Flow<LlmEvent> = flow {
            calls++
            when (val next = left.removeFirst()) {
                is StepOutput -> emit(LlmEvent.Done(next))
                is Throwable -> throw next
                else -> error("剧本里只能放 StepOutput 或异常")
            }
        }
    }

    data class Noted(val arguments: String)

    private class NoteTool(override val name: String = "note_down") : Tool {
        override val description = "记一句"
        override val parameters: Map<String, Any?> = mapOf(
            "type" to "object", "properties" to emptyMap<String, Any?>(), "required" to emptyList<String>(), "additionalProperties" to false
        )
        override val effect = ToolEffect.WRITE
        override suspend fun execute(arguments: String, context: ToolContext) =
            ToolOutcome("记下了", ok = true, ref = 7, payload = Noted(arguments))
    }

    private object NoMemory : MemoryBackend {
        override suspend fun all(): List<Memory> = emptyList()
        override suspend fun apply(edit: MemoryEdit, source: String): MemoryApplied = error("测试里不该写记忆")
    }

    private var clientsBuilt = 0

    private fun install(
        llm: LlmClient,
        profile: ProviderProfile = configured,
        agents: List<AgentInfo> = listOf(Helper),
        strict: Boolean = true
    ) {
        clientsBuilt = 0
        AgentRuntime.install(agents, { profile }, { NoMemory }, strict = strict, clientOf = { clientsBuilt++; llm })
    }

    private fun call(name: String = "note_down") = StepOutput("", listOf(Item.ToolCall("c-$name", name, "{}")), "")
    private fun say(text: String) = StepOutput(text, emptyList(), "")

    @Before
    fun reset() = ApiHealth.reset()

    @Test
    fun `runOnce collects what the tools did and the last reply`() = runBlocking {
        install(ScriptedLlm(call(), say("记好了")))
        val seen = mutableListOf<ToolOutcome>()
        val result = AgentRuntime.runOnce(Agent(Helper, NOTE, listOf(NoteTool())), Input("记一下", now)) { seen += it }

        assertTrue("应该正常结束：$result", result is RunResult.Done)
        assertEquals(listOf(Noted("{}")), result.payloads<Noted>())
        assertEquals("边跑边报，一个工具一次", 1, seen.size)
        assertEquals("记好了", result.reply)
        assertEquals("一轮结束自己记健康", ApiState.Ok, ApiHealth.state.value)
    }

    @Test
    fun `runOnce does not touch the model when nothing is configured`() = runBlocking {
        val llm = ScriptedLlm()
        install(llm, profile = ProviderProfile("", "", ""))
        val result = AgentRuntime.runOnce(Agent(Helper, NOTE, listOf(NoteTool())), Input("记一下", now))
        assertEquals(RunResult.NotConfigured, result)
        assertEquals(0, llm.calls)
        assertEquals(0, clientsBuilt)
    }

    @Test
    fun `a failed call comes back as Failed and marks the link down`() = runBlocking {
        install(ScriptedLlm(LlmException("401 key 不对")))
        val result = AgentRuntime.runOnce(Agent(Helper, NOTE, listOf(NoteTool())), Input("记一下", now))
        assertTrue("应该是失败：$result", result is RunResult.Failed)
        assertNull(result.reply)
        assertTrue("链路断了印要变灰：${ApiHealth.state.value}", ApiHealth.state.value is ApiState.Down)
    }

    @Test
    fun `run streams the turn for the screen`() = runBlocking {
        install(ScriptedLlm(say("你好")))
        val events = AgentRuntime.run(Agent(Helper, NOTE, listOf(NoteTool())), Session(), Input("在吗", now)).toList()
        assertTrue(events.last() is AgentEvent.Finished)
        assertEquals(ApiState.Ok, ApiHealth.state.value)
    }

    @Test
    fun `the client is shared while the profile stays the same`() = runBlocking {
        val llm = ScriptedLlm(say("一"), say("二"), say("三"))
        install(llm)
        val agent = Agent(Helper, NOTE, listOf(NoteTool()))
        AgentRuntime.runOnce(agent, Input("一", now))
        AgentRuntime.runOnce(agent, Input("二", now))
        assertEquals("同一份配置只建一个客户端", 1, clientsBuilt)
    }

    @Test
    fun `strict mode stops an agent that is not on the roster`() = runBlocking {
        install(ScriptedLlm(say("你好")), agents = emptyList())
        val message = failure { AgentRuntime.runOnce(Agent(Helper, NOTE, listOf(NoteTool())), Input("在吗", now)) }
        assertTrue(message, "不在花名册上" in message)
    }

    @Test
    fun `strict mode stops tools that do not match the prompt`() = runBlocking {
        install(ScriptedLlm(say("你好")))
        val message = failure {
            AgentRuntime.runOnce(Agent(Helper, NOTE, listOf(NoteTool(), NoteTool("extra_tool"))), Input("在吗", now))
        }
        assertTrue(message, "多了 [extra_tool]" in message)
    }

    @Test
    fun `strict mode stops ask_user on an agent that cannot ask`() = runBlocking {
        install(ScriptedLlm(say("你好")))
        val message = failure {
            AgentRuntime.runOnce(Agent(Helper, ASKING, listOf(NoteTool(), AskUserTool())), Input("在吗", now))
        }
        assertTrue(message, "问不了人" in message)
    }

    @Test
    fun `without strict a mismatch still runs`() = runBlocking {
        install(ScriptedLlm(say("你好")), strict = false)
        val result = AgentRuntime.runOnce(Agent(Helper, NOTE, listOf(NoteTool(), NoteTool("extra_tool"))), Input("在吗", now))
        assertTrue(result is RunResult.Done)
    }

    @Test
    fun `exclusive skips while someone holds the lock and shows who is running`() = runBlocking {
        val inner = AgentRuntime.exclusive("test-lock", "outer", "外层") {
            assertEquals(listOf("outer"), AgentRuntime.running.value.map { it.id })
            AgentRuntime.exclusive("test-lock", "inner", "里层") { "跑了" }
        }
        assertNull("锁被拿着时不排队，直接不跑", inner)
        assertTrue("跑完摘掉", AgentRuntime.running.value.isEmpty())
    }

    private suspend fun failure(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: IllegalStateException) {
            return e.message.orEmpty()
        }
        fail("应该在跑之前就停下")
        error("走不到")
    }
}
