package com.abc.daodian.reminder.tools

import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.domain.Checklist
import com.abc.daodian.runtime.tool.ToolContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 清单（§4.5）：建提醒时顺手列、list_reminders 带号列出来、change_checklist 按号勾 / 加 / 删，和它们的痕 */
class ChecklistToolsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.parse("2026-11-17T10:00:00+08:00[Asia/Shanghai]")
    private val ctx = ToolContext(now, "")

    private fun desk() = FakeReminderDesk(
        listOf(
            Reminder(
                id = 4, title = "去超市", nextTriggerAt = 0, zoneId = zone.id, wallClockAnchored = true,
                day = LocalDate.parse("2026-11-21"), rrule = "FREQ=WEEKLY;BYDAY=SA", createdAt = 0, updatedAt = 0
            )
        )
    ).apply { add(4, "鸡蛋"); add(4, "牛奶"); add(4, "洗衣液", done = true) }

    private fun createArgs(items: String) =
        """{"title":"收拾行李","day":"2026-12-23","times":[],"basis":"12-23","note":null,"rrule":null,""" +
            """"remindBefore":[],"ring":"notice","nag":"daily","wallClockAnchored":true,"items":[$items]}"""

    @Test
    fun `create lists the checklist with the reminder and the trace counts it`() = runBlocking {
        val d = desk()
        val tool = CreateReminderTool(d::create)
        val args = createArgs("\"护照签证\",\"转换插头\",\" \"")
        val out = tool.execute(args, ctx)
        assertTrue(out.output, out.ok && out.output.endsWith("清单 2 样：护照签证、转换插头"))
        assertEquals(listOf("护照签证", "转换插头"), d.checklist.filter { it.reminderId == out.ref }.map { it.text })

        val trace = ReminderTrace.of(ToolTrace(CreateReminderTool.NAME, args, TraceState.OK, out.output, out.ref))!!
        assertTrue(trace.text, trace.text.endsWith("收拾行李 · 清单 2 样"))
        assertEquals(listOf(trace.text.removeSuffix(" · 清单 2 样"), "清单：护照签证、转换插头"), trace.lines)
    }

    @Test
    fun `create turns back a list that is too long, notice agent gets no items parameter`() = runBlocking {
        val tool = CreateReminderTool(desk()::create)
        val many = (1..31).joinToString(",") { "\"东西$it\"" }
        val out = tool.execute(createArgs(many), ctx)
        assertFalse(out.ok)
        assertTrue(out.output, "最多 ${Checklist.MAX_ITEMS} 样" in out.output && "改好再调一次" in out.output)

        val notice = ReminderTools.forNotice { _, _ -> 1L }.single()
        @Suppress("UNCHECKED_CAST")
        val props = notice.parameters["properties"] as Map<String, Any?>
        assertFalse("items" in props)
    }

    @Test
    fun `list shows the checklist numbered under its reminder`() = runBlocking {
        val out = ListRemindersTool(desk()) { zone }.execute(
            """{"keyword":null,"from":null,"to":null,"include_finished":false,"with_checklist":false,"limit":null}""", ctx
        )
        val lines = out.output.lines()
        assertTrue(lines[1], lines[1].startsWith("#4 去超市"))
        assertEquals("  清单：1[ ]鸡蛋 2[ ]牛奶 3[x]洗衣液", lines[2])
    }

    @Test
    fun `with_checklist lists only reminders that have one, finished ones too`() = runBlocking {
        val d = FakeReminderDesk(
            listOf(
                Reminder(
                    id = 4, title = "去超市", nextTriggerAt = 0, zoneId = zone.id, wallClockAnchored = true,
                    day = LocalDate.parse("2026-11-21"), createdAt = 0, updatedAt = 0
                ),
                Reminder(
                    id = 5, title = "吃药", nextTriggerAt = 0, zoneId = zone.id, wallClockAnchored = true,
                    day = LocalDate.parse("2026-11-21"), createdAt = 0, updatedAt = 0
                ),
                Reminder(
                    id = 6, title = "出差收拾行李", nextTriggerAt = 0, zoneId = zone.id, wallClockAnchored = true,
                    day = LocalDate.parse("2026-10-02"), status = ReminderStatus.DONE, createdAt = 0, updatedAt = 0
                )
            )
        ).apply { add(4, "鸡蛋"); add(6, "充电器", done = true); add(6, "降噪耳机", done = true) }
        val tool = ListRemindersTool(d) { zone }
        val out = tool.execute("""{"keyword":null,"from":null,"to":null,"include_finished":true,"with_checklist":true,"limit":null}""", ctx).output
        assertTrue(out, out.startsWith("共 2 条") && "#4 去超市" in out && "#6 出差收拾行李" in out && "2[x]降噪耳机" in out)
        assertFalse(out, "吃药" in out)
        val none = FakeReminderDesk(emptyList())
        assertEquals("没有符合条件的、还没响的带清单的提醒。", ListRemindersTool(none) { zone }.execute(
            """{"keyword":null,"from":null,"to":null,"include_finished":false,"with_checklist":true,"limit":null}""", ctx
        ).output)
    }

    @Test
    fun `change checks, adds and removes by number and says what is left`() = runBlocking {
        val d = desk()
        val args = """{"id":4,"check":[1,2],"uncheck":[],"add":["洗洁精"],"remove":[3]}"""
        val out = ChangeChecklistTool(d).execute(args, ctx)
        assertTrue(out.output, out.ok)
        assertEquals("已改好「去超市」（id=4）的清单：勾了 2 样：鸡蛋、牛奶；加了 1 样：洗洁精；删了 1 样：洗衣液。还剩 1 样。", out.output)
        assertEquals(listOf("鸡蛋" to true, "牛奶" to true, "洗洁精" to false), d.checklist.map { it.text to it.done })

        val trace = ReminderTrace.of(ToolTrace(ChangeChecklistTool.NAME, args, TraceState.OK, out.output, 4))!!
        assertEquals("去超市 · 勾了 2 样 · 加了 1 样 · 删了 1 样", trace.text)
        assertEquals(listOf("勾了 · 鸡蛋、牛奶", "加了 · 洗洁精", "删了 · 洗衣液"), trace.lines)
    }

    @Test
    fun `change with nothing to do or unknown numbers goes back to the model`() = runBlocking {
        val d = desk()
        val same = ChangeChecklistTool(d).execute("""{"id":4,"check":[3],"uncheck":[],"add":[],"remove":[]}""", ctx)
        assertFalse(same.ok)
        assertTrue(same.output, "清单没变" in same.output)
        val unknown = ChangeChecklistTool(d).execute("""{"id":4,"check":[9],"uncheck":[],"add":[],"remove":[]}""", ctx)
        assertFalse(unknown.ok)
        assertTrue(unknown.output, "没有 9 号" in unknown.output)
        val missing = ChangeChecklistTool(d).execute("""{"id":99,"check":[1],"uncheck":[],"add":[],"remove":[]}""", ctx)
        assertTrue(missing.output, !missing.ok && "没有 #99" in missing.output)
    }
}
