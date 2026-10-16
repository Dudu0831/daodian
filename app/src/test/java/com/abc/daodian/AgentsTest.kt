package com.abc.daodian

import com.abc.daodian.assistant.AssistantAgent
import com.abc.daodian.assistant.AssistantPrompt
import com.abc.daodian.assistant.memory.FakeMemoryBook
import com.abc.daodian.assistant.memory.tidy.TidyAgent
import com.abc.daodian.ledger.domain.LedgerBackend
import com.abc.daodian.ledger.organize.OrganizerAgent
import com.abc.daodian.ledger.tools.LedgerTools
import com.abc.daodian.reminder.relay.RelayAgent
import com.abc.daodian.reminder.tools.ReminderTools
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.runtime.Input
import com.abc.daodian.runtime.RunResult
import com.abc.daodian.runtime.model.LlmClient
import com.abc.daodian.runtime.model.LlmEvent
import com.abc.daodian.runtime.model.LlmRequest
import com.abc.daodian.runtime.model.ProviderProfile
import com.abc.daodian.runtime.model.StepOutput
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 花名册（[AGENTS]）上真的那几个 agent，照 debug 包的规矩（`AgentRuntime` 的 strict）查一遍：
 * 提示词在它自己的 prompts 里、手上的工具和提示词写的对得上、问不了人的没带 ask_user。
 * 不用装机就知道 debug 包会不会一跑就崩。
 *
 * 工具要连库，这里用假的现建。助手的工具是各模块接头给的（`XxxPlugin.tools` 要 Context，测试里建不出来），
 * 这里调的是它们背后同一个工厂（[ReminderTools.forAssistant]、[LedgerTools.forAssistant]），按 `Modules.kt` 的顺序拼。
 * 模块加了插件（新的 `XxxPlugin`）这里要跟着加一段。
 */
class AgentsTest {

    private val now = ZonedDateTime.of(2026, 10, 15, 15, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    /** 建工具不该碰库：碰了就报出来 */
    private val ledger = Proxy.newProxyInstance(LedgerBackend::class.java.classLoader, arrayOf(LedgerBackend::class.java)) { _, method, _ ->
        error("建工具时不该碰账本：${method.name}")
    } as LedgerBackend

    /** 模型只回一句话，不调工具 —— 这里只看跑之前那道检查 */
    private val quiet = object : LlmClient {
        override fun step(request: LlmRequest): Flow<LlmEvent> = flowOf(LlmEvent.Done(StepOutput("好", emptyList(), "")))
    }

    private fun strictRun(agent: Agent): RunResult = runBlocking {
        AgentRuntime.install(
            AGENTS, { ProviderProfile("https://example.test/v1", "key", "test-model") }, { FakeMemoryBook() },
            strict = true, clientOf = { quiet }
        )
        AgentRuntime.runOnce(agent, Input("测一下", now))
    }

    @Test
    fun `background agents pass the strict checks`() {
        listOf(
            OrganizerAgent.build(ledger, { "test" }, tagging = false),
            OrganizerAgent.build(ledger, { "test" }, tagging = true),
            RelayAgent.build { _, _ -> 1L },
            TidyAgent.build(FakeMemoryBook(), compact = true, remember = true)
        ).forEach { agent ->
            val result = strictRun(agent)
            assertTrue("「${agent.info.label}」（${agent.prompt.version}）没过检查：$result", result is RunResult.Done)
        }
    }

    /** 和真机上一样的一套：助手自己的两样 + 提醒、记账给的 */
    private fun assistantTools() =
        AssistantAgent.tools(FakeMemoryBook(), ReminderTools.forAssistant { _, _ -> 1L } + LedgerTools.forAssistant(ledger))

    @Test
    fun `the assistant prompt is written for the tools the modules hand over`() {
        assertEquals(AssistantPrompt.TOOLS, assistantTools().map { it.name }.toSet())
    }

    @Test
    fun `the assistant passes the strict checks`() {
        val result = strictRun(AssistantAgent.build(assistantTools()))
        assertTrue("助手没过检查：$result", result is RunResult.Done)
    }

    @Test
    fun `the roster lists the four agents once each`() {
        assertEquals(listOf("assistant", "organizer", "relay", "tidy"), AGENTS.map { it.id })
        assertTrue("只有助手能问人", AGENTS.filter { it.canAsk }.map { it.id } == listOf("assistant"))
    }
}
