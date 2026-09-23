package com.abc.daodian.agent.conversation

import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn
import com.abc.daodian.agent.engine.ask.AskAnswer
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.ask.Pick
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.agent.model.LlmEvent
import com.abc.daodian.agent.engine.tool.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 一个回合怎么随事件长大，重启后怎么画回来 */
class ChatMessageTest {

    private val now = ZonedDateTime.parse("2026-09-23T21:30:00+08:00[Asia/Shanghai]")
    private val askArgs = """{"label":"4 笔","questions":[{"context":null,"amount":"¥36.50","question":null,"hint":null,"options":[{"label":"午饭","detail":null}]}]}"""
    private val reminderArgs = """{"title":"带伞","firstTriggerAt":"2026-09-24T08:00:00+08:00","basis":"b","note":null,"rrule":null,"wallClockAnchored":false,"allDay":false}"""

    private fun ChatMessage.AssistantTurn.with(vararg events: AgentEvent) = events.fold(this) { t, e -> t.patched(e) }

    @Test
    fun `blocks stack in arrival order and a new step starts a new prose block`() {
        val call = Item.ToolCall("c1", CreateReminderTool.NAME, reminderArgs)
        val t = ChatMessage.AssistantTurn(1, streaming = true).with(
            AgentEvent.Model(0, LlmEvent.ToolStarted("c1", CreateReminderTool.NAME)),
            AgentEvent.ToolStarting(call),
            AgentEvent.ToolFinished(call, ToolOutcome("已建好", ok = true, ref = 7)),
            AgentEvent.Model(1, LlmEvent.Text("好，")),
            AgentEvent.Model(1, LlmEvent.Text("明早叫你。"))
        )

        assertEquals(2, t.blocks.size)
        val trace = t.blocks[0] as TurnBlock.Trace
        assertEquals(TraceState.OK, trace.state)
        assertEquals(7L, trace.ref)
        assertEquals("好，明早叫你。", (t.blocks[1] as TurnBlock.Prose).text)
        assertEquals(7L, t.createdReminderId)
        assertTrue(t.committed)
    }

    @Test
    fun `waiting shows ink bars except while an ask card waits for you`() {
        val t = ChatMessage.AssistantTurn(1, streaming = true)
        assertTrue(t.waiting)
        val asking = t.with(AgentEvent.Model(0, LlmEvent.ToolStarted("a1", AskUserTool.NAME)))
            .withAsk("a1") { it.copy(state = AskState.READY) }
        assertTrue(!asking.waiting)
        // 答完了，模型接着办：墨条回来
        assertTrue(asking.answered("a1", AskAnswer.Picked(listOf(null))).waiting)
    }

    @Test
    fun `falling back mid-turn only wipes that step`() {
        val t = ChatMessage.AssistantTurn(1, streaming = true).with(
            AgentEvent.Model(0, LlmEvent.Text("先说一句")),
            AgentEvent.Model(1, LlmEvent.Text("半截")),
            AgentEvent.Model(1, LlmEvent.FellBack)
        )
        assertEquals(listOf("先说一句"), t.blocks.map { (it as TurnBlock.Prose).text })
    }

    @Test
    fun `restored ask cards come back answered, said, or unanswered`() {
        fun turn(id: Long, answer: AskAnswer?) = Turn(
            id,
            listOfNotNull(
                Item.UserMessage("对账", now, trigger = true),
                Item.ToolCall("a$id", AskUserTool.NAME, askArgs),
                answer?.let { Item.ToolResult("a$id", AskUserTool.outputOf(AskUserTool.requestOf(askArgs), it), ok = it != AskAnswer.Unanswered) }
            )
        )
        var n = 0L
        val msgs = restoredMessages(
            listOf(turn(1, AskAnswer.Picked(listOf(Pick.Option(0)))), turn(2, AskAnswer.Said("午饭")), turn(3, null)),
            newId = { n++ }
        ).filterIsInstance<ChatMessage.AssistantTurn>()

        val first = msgs[0].blocks.single() as TurnBlock.Ask
        assertEquals(AskState.ANSWERED, first.state)
        assertEquals(listOf<Pick?>(Pick.Option(0)), first.picks)
        assertEquals(AskState.SAID, (msgs[1].blocks[0] as TurnBlock.Ask).state)
        assertEquals("午饭", (msgs[1].blocks[1] as TurnBlock.Said).text)
        assertEquals(AskState.UNANSWERED, (msgs[2].blocks.single() as TurnBlock.Ask).state)
    }

    @Test
    fun `an immediate retry hides the failed attempt, a retry after asking keeps it`() {
        fun trace(id: String, state: TraceState) = TurnBlock.Trace(id, CreateReminderTool.NAME, 0, state = state)
        val retried = ChatMessage.AssistantTurn(1, blocks = listOf(trace("c1", TraceState.FAILED), trace("c2", TraceState.OK)))
        assertEquals(listOf("c2"), retried.visibleBlocks.map { it.key })

        val asked = ChatMessage.AssistantTurn(
            1,
            blocks = listOf(trace("c1", TraceState.FAILED), TurnBlock.Ask("a1", 0, state = AskState.ANSWERED), trace("c2", TraceState.OK))
        )
        assertEquals(listOf("c1", "a1", "c2"), asked.visibleBlocks.map { it.key })
    }
}
