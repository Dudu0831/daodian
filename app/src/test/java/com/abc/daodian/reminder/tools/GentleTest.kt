package com.abc.daodian.reminder.tools

import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceState
import com.abc.daodian.reminder.domain.ReminderPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 轻提醒（gentle）：模型的参数怎么读、落库前带没带上、痕上怎么写。见 DESIGN.md §5.6 */
class GentleTest {

    private val now = ZonedDateTime.parse("2026-10-09T11:20:00+08:00[Asia/Shanghai]")

    private fun args(gentle: String?, allDay: Boolean = false) = buildString {
        append("""{"title":"喝水","firstTriggerAt":"2026-10-09T""")
        append(if (allDay) "00:00:00" else "14:00:00")
        append("""+08:00","basis":"今天 14:00","note":null,"rrule":"FREQ=DAILY","wallClockAnchored":true,"allDay":$allDay""")
        if (gentle != null) append(""","gentle":$gentle""")
        append("}")
    }

    @Test
    fun `history without the parameter reads as ringing`() {
        assertFalse(CreateReminderTool.planOf(args(gentle = null))!!.gentle)
        assertFalse(CreateReminderTool.planOf(args(gentle = "false"))!!.gentle)
        assertTrue(CreateReminderTool.planOf(args(gentle = "true"))!!.gentle)
    }

    @Test
    fun `gentle reaches the committer and the model is told`() = runBlocking {
        val committed = mutableListOf<ReminderPlan>()
        val tool = CreateReminderTool { plan, _ -> committed += plan; 7L }

        val gentle = tool.execute(args(gentle = "true"), ToolContext(now, ""))
        assertTrue(gentle.output, gentle.ok)
        assertTrue(committed.single().gentle)
        assertTrue(gentle.output, "轻提醒" in gentle.output)

        val ringing = tool.execute(args(gentle = "false"), ToolContext(now, ""))
        assertFalse(committed.last().gentle)
        assertFalse(ringing.output, "轻提醒" in ringing.output)
    }

    @Test
    fun `trace names it before the title`() {
        fun text(arguments: String) =
            ReminderTrace.of(ToolTrace(CreateReminderTool.NAME, arguments, TraceState.OK, null, 7L))!!.text

        assertTrue(text(args(gentle = "true")), text(args(gentle = "true")).endsWith(" · 轻提醒 · 喝水"))
        assertFalse("轻提醒" in text(args(gentle = "false")))
        assertEquals(text(args(gentle = "false")), text(args(gentle = null)))
        // 当天事项本来就只发通知，模型多填了也不写
        assertFalse("轻提醒" in text(args(gentle = "true", allDay = true)))
    }
}
