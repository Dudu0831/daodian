package com.abc.daodian.harness

import com.abc.daodian.harness.builtin.reminder.CreateReminderTool
import com.abc.daodian.harness.context.LastTurns
import com.abc.daodian.harness.llm.LlmClient
import com.abc.daodian.harness.llm.LlmEvent
import com.abc.daodian.harness.llm.LlmException
import com.abc.daodian.harness.llm.LlmRequest
import com.abc.daodian.harness.llm.StepOutput
import com.abc.daodian.harness.permission.Approval
import com.abc.daodian.harness.permission.Approver
import com.abc.daodian.harness.permission.PermissionGate
import com.abc.daodian.harness.permission.PermissionMode
import com.abc.daodian.harness.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 循环本身的行为。模型是照剧本念的假货，不打网络 */
class AgentLoopTest {

    private val now = ZonedDateTime.parse("2026-09-18T21:00:00+08:00[Asia/Shanghai]")

    private val validArgs = """{"title":"交房租","firstTriggerAt":"2026-09-19T15:00:00+08:00","basis":"明天 15:00",
        |"note":null,"rrule":null,"wallClockAnchored":false,"allDay":false}""".trimMargin()

    /** 每调一次吐剧本里的下一步，并记下收到的请求 */
    private class ScriptedLlm(private vararg val steps: StepOutput) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override fun step(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            val out = steps.getOrNull(requests.size - 1) ?: throw LlmException("剧本念完了")
            emit(LlmEvent.Done(out))
        }
    }

    private fun call(id: String, args: String) = Item.ToolCall(id, CreateReminderTool.NAME, args)
    private fun answer(text: String) = StepOutput(text, emptyList(), "")
    private fun calls(vararg c: Item.ToolCall) = StepOutput("", c.toList(), "")

    private val committed = mutableListOf<String>()
    private val tools = ToolRegistry(listOf(CreateReminderTool { plan, _ -> committed += plan.title; 42L }))

    private val auto = PermissionGate(PermissionMode.AUTO) { _, _ -> error("AUTO 不该问") }

    @Test
    fun `tool result goes back to the model and the loop ends on a plain answer`() = runBlocking {
        val llm = ScriptedLlm(calls(call("c1", validArgs)), answer("好了，明天 15:00 提醒你交房租"))
        val session = Session()

        val events = AgentLoop(llm, tools, "sys").run(session, "明天三点提醒我交房租", now, auto).toList()

        assertEquals(listOf("交房租"), committed)
        assertEquals(2, llm.requests.size)
        val second = llm.requests[1].input
        assertTrue(second[1] is Item.ToolCall)
        assertTrue((second[2] as Item.ToolResult).output.startsWith("已建好（id=42）"))
        val finished = events.last() as AgentEvent.Finished
        assertEquals(StopReason.ANSWERED, finished.stop)
        assertEquals(4, finished.turn.items.size)       // 用户话、调用、结果、回答
    }

    @Test
    fun `ask mode - denied write tool is not executed and the model is told`() = runBlocking {
        val llm = ScriptedLlm(calls(call("c1", validArgs)), answer("好，不建了"))
        val deny = PermissionGate(PermissionMode.ASK) { _, _ -> Approval.Denied }
        val session = Session()

        val events = AgentLoop(llm, tools, "sys").run(session, "明天三点提醒我交房租", now, deny).toList()

        assertTrue(committed.isEmpty())
        assertTrue(events.any { it is AgentEvent.AwaitingApproval })
        assertTrue(events.any { it is AgentEvent.ToolDenied })
        assertEquals(AgentLoop.DENIED, session.turns.single().items.filterIsInstance<Item.ToolResult>().single().output)
    }

    @Test
    fun `ask mode - approved write tool runs`() = runBlocking {
        val llm = ScriptedLlm(calls(call("c1", validArgs)), answer("好了"))
        val allow = PermissionGate(PermissionMode.ASK) { _, _ -> Approval.Approved }

        AgentLoop(llm, tools, "sys").run(Session(), "明天三点提醒我交房租", now, allow).toList()

        assertEquals(listOf("交房租"), committed)
    }

    @Test
    fun `ask mode - redirect ends the turn without another model call`() = runBlocking {
        val llm = ScriptedLlm(calls(call("c1", validArgs), call("c2", validArgs)), answer("不该走到这"))
        val redirect = PermissionGate(PermissionMode.ASK) { _, _ -> Approval.Redirected("改成四点") }
        val session = Session()

        val events = AgentLoop(llm, tools, "sys").run(session, "明天三点提醒我交房租", now, redirect).toList()

        assertTrue(committed.isEmpty())
        assertEquals(1, llm.requests.size)
        assertEquals(StopReason.REDIRECTED, (events.last() as AgentEvent.Finished).stop)
        val results = session.turns.single().items.filterIsInstance<Item.ToolResult>()
        assertEquals(listOf(AgentLoop.REDIRECTED, AgentLoop.ABORTED), results.map { it.output })
    }

    @Test
    fun `validator rejection is fed back instead of committing`() = runBlocking {
        val past = validArgs.replace("2026-09-19T15:00", "2026-09-17T15:00")
        val llm = ScriptedLlm(calls(call("c1", past)), answer("你是指哪天？"))
        val session = Session()

        AgentLoop(llm, tools, "sys").run(session, "昨天三点提醒我", now, auto).toList()

        assertTrue(committed.isEmpty())
        val result = session.turns.single().items.filterIsInstance<Item.ToolResult>().single()
        assertTrue(result.output, result.output.startsWith("没建"))
    }

    @Test
    fun `step limit stops a model that keeps calling tools`() = runBlocking {
        val llm = ScriptedLlm(calls(call("a", "{}")), calls(call("b", "{}")), calls(call("c", "{}")))

        val events = AgentLoop(llm, tools, "sys", maxSteps = 3).run(Session(), "x", now, auto).toList()

        assertEquals(StopReason.STEP_LIMIT, (events.last() as AgentEvent.Finished).stop)
        assertEquals(3, llm.requests.size)
    }

    @Test
    fun `model failure ends the turn with Failed`() = runBlocking {
        val events = AgentLoop(ScriptedLlm(), tools, "sys").run(Session(), "x", now, auto).toList()

        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test
    fun `context keeps only the last n whole turns`() = runBlocking {
        val session = Session()
        val loop = { llm: ScriptedLlm -> AgentLoop(llm, tools, "sys", context = LastTurns(2)) }
        loop(ScriptedLlm(calls(call("c1", validArgs)), answer("好了"))).run(session, "第一句", now, auto).toList()
        loop(ScriptedLlm(answer("嗯"))).run(session, "第二句", now, auto).toList()
        val third = ScriptedLlm(answer("嗯"))
        loop(third).run(session, "第三句", now, auto).toList()

        val input = third.requests.single().input
        val said = input.filterIsInstance<Item.UserMessage>().map { it.text }
        assertEquals(listOf("第二句", "第三句"), said)
        assertTrue(input.first() is Item.UserMessage)          // 从整轮开头切，不会切出孤立的工具结果
        assertEquals(3, session.turns.size)                     // 裁剪只影响喂给模型的，不删记录
    }

    @Test
    fun `stopping while waiting for approval closes the dangling call`() = runBlocking {
        val llm = ScriptedLlm(calls(call("c1", validArgs)))
        val never = CompletableDeferred<Approval>()
        val ask = PermissionGate(PermissionMode.ASK, Approver { _, _ -> never.await() })
        val session = Session()

        val waiting = CompletableDeferred<Unit>()
        val job = AgentLoop(llm, tools, "sys").run(session, "明天三点提醒我交房租", now, ask)
            .onEach { if (it is AgentEvent.AwaitingApproval) waiting.complete(Unit) }
            .launchIn(this)
        waiting.await()
        yield()
        job.cancel()
        job.join()

        val items = session.turns.single().items
        assertTrue(committed.isEmpty())
        assertEquals(AgentLoop.ABORTED, items.filterIsInstance<Item.ToolResult>().single().output)
    }

    @Test
    fun `session reports every change so it can be persisted`() = runBlocking {
        val seen = mutableListOf<Turn>()
        val discarded = mutableListOf<Long>()
        val session = Session(listener = object : Session.Listener {
            override fun changed(turn: Turn) { seen += turn }
            override fun discarded(turn: Turn) { discarded += turn.id }
        })
        val llm = ScriptedLlm(calls(call("c1", validArgs)), answer("好了"))

        AgentLoop(llm, tools, "sys").run(session, "明天三点提醒我交房租", now, auto).toList()

        // 开轮、调用、结果、回答，各报一次；最后一份快照就是整轮
        assertEquals(4, seen.size)
        assertEquals(session.turns.single(), seen.last())
        val result = seen.last().items.filterIsInstance<Item.ToolResult>().single()
        assertTrue(result.ok)
        assertEquals(42L, result.ref)

        session.discardLastTurn()
        assertEquals(listOf(seen.last().id), discarded)
    }

    @Test
    fun `turn ids keep counting after a restored history`() = runBlocking {
        val restored = listOf(Turn(7, listOf(Item.UserMessage("旧的", now))))
        val session = Session(restored)

        AgentLoop(ScriptedLlm(answer("嗯")), tools, "sys").run(session, "新的", now, auto).toList()

        assertEquals(listOf(7L, 8L), session.turns.map { it.id })
    }

    @Test
    fun `user message carries its own timestamp`() {
        val content = Item.UserMessage("明天提醒我", now).content()
        assertEquals("[2026-09-18 21:00 周五 +08:00] 明天提醒我", content)
    }
}
