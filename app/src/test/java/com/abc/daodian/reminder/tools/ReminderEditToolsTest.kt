package com.abc.daodian.reminder.tools

import java.time.LocalDate
import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.runtime.tool.ToolContext
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 助手查、改、停、删提醒（list_reminders / update_reminder / stop_reminders / delete_reminders）和它们的痕 */
class ReminderEditToolsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.parse("2026-11-13T10:00:00+08:00[Asia/Shanghai]")
    private val ctx = ToolContext(now, "")

    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    private fun desk() = FakeReminderDesk(
        listOf(
            Reminder(
                id = 3, title = "喝水", nextTriggerAt = at("2026-11-14T10:00:00+08:00"), rrule = "FREQ=DAILY",
                zoneId = zone.id, wallClockAnchored = true, day = LocalDate.parse("2026-11-14"), times = "10:00", slot = "10:00",
                ring = Ring.NOTICE, createdAt = 0, updatedAt = 0
            ),
            Reminder(
                id = 5, title = "交房租", note = "转给房东", nextTriggerAt = at("2026-11-20T15:00:00+08:00"),
                zoneId = zone.id, day = LocalDate.parse("2026-11-20"), times = "15:00", slot = "15:00", createdAt = 0, updatedAt = 0
            ),
            Reminder(
                id = 7, title = "报销", nextTriggerAt = at("2026-11-13T20:00:00+08:00"), day = LocalDate.parse("2026-11-11"),
                zoneId = zone.id, wallClockAnchored = true, ring = Ring.NOTICE, nag = Nag.DAILY, phase = Phase.NAG, nagCount = 2,
                createdAt = 0, updatedAt = 0
            ),
            Reminder(
                id = 9, title = "开会", nextTriggerAt = at("2026-11-12T09:00:00+08:00"), status = ReminderStatus.DONE,
                zoneId = zone.id, day = LocalDate.parse("2026-11-12"), times = "09:00", slot = "09:00", createdAt = 0, updatedAt = 5
            )
        )
    )

    private fun list(desk: ReminderDesk) = ListRemindersTool(desk) { zone }

    private fun query(keyword: String? = null, from: String? = null, to: String? = null, finished: Boolean = false) =
        """{"keyword":${keyword?.let { "\"$it\"" }},"from":${from?.let { "\"$it\"" }},"to":${to?.let { "\"$it\"" }},"include_finished":$finished,"limit":null}"""

    @Test
    fun `list shows the live ones in time order with the values update copies`() = runBlocking {
        val out = list(desk()).execute(query(), ctx)
        assertTrue(out.ok)
        val lines = out.output.lines()
        assertEquals("共 3 条：", lines[0])
        assertTrue(lines[1], lines[1].startsWith("#7 报销 · ") && "拖了 2 天" in lines[1] && "没点完成每晚再提" in lines[1])
        // 拖过来的：抄过去的日子是今天，不然闸门当它在过去
        assertTrue(lines[1], lines[1].endsWith("| day=2026-11-13 times=[] rrule=null remindBefore=[] ring=notice nag=daily wallClockAnchored=true"))
        assertTrue(lines[2], lines[2].startsWith("#3 喝水 · 每天 10:00 · 下次 11月14日 周六 10:00 · 只发通知"))
        assertTrue(lines[2], lines[2].endsWith("| day=2026-11-14 times=[10:00] rrule=FREQ=DAILY remindBefore=[] ring=notice nag=none wallClockAnchored=true"))
        assertTrue(lines[3], "备注：转给房东" in lines[3] && "rrule=null" in lines[3] && "ring=alarm" in lines[3])
        assertFalse("完成了的默认不列", "开会" in out.output)
    }

    @Test
    fun `list filters by keyword, day and finished`() = runBlocking {
        val d = desk()
        assertTrue(list(d).execute(query(keyword = "房东"), ctx).output.contains("#5 交房租"))
        val day = list(d).execute(query(from = "2026-11-14", to = "2026-11-14"), ctx).output
        assertTrue(day, day.startsWith("共 1 条") && "#3" in day)
        val done = list(d).execute(query(keyword = "开会", finished = true), ctx).output
        assertTrue(done, "#9 开会" in done && "完成了" in done)
        assertEquals("没有符合条件的、还没响的提醒。", list(d).execute(query(keyword = "健身"), ctx).output)
    }

    private fun plan(id: Long, day: String, time: String = "16:00", title: String = "交房租") =
        """{"id":$id,"title":"$title","day":"$day","times":["$time"],"basis":"原来 11-20 15:00 推后一小时","note":"转给房东",""" +
            """"rrule":null,"remindBefore":[],"ring":"alarm","nag":"none","wallClockAnchored":false}"""

    @Test
    fun `update rewrites the whole reminder through the gate`() = runBlocking {
        val d = desk()
        val out = UpdateReminderTool(d).execute(plan(5, "2026-11-20"), ctx)
        assertTrue(out.output, out.ok)
        assertEquals(5L, out.ref)
        val r = d.reminders.single { it.id == 5L }
        assertEquals("16:00", r.times)
        assertFalse(r.wallClockAnchored)
        assertEquals("已改好（id=5）：「交房租」，2026-11-20 16:00，不重复，响铃，不催，固定这一瞬间（不跟时区走）。", out.output)
    }

    @Test
    fun `update refuses the past and unknown ids`() = runBlocking {
        val d = desk()
        val past = UpdateReminderTool(d).execute(plan(5, "2026-11-12"), ctx)
        assertFalse(past.ok)
        assertTrue(past.output, past.output.startsWith("没改。算出来是 11月12日，已经过去了"))
        assertTrue(d.updates.isEmpty())

        val missing = UpdateReminderTool(d).execute(plan(42, "2026-11-20"), ctx)
        assertFalse(missing.ok)
        assertTrue(missing.output, "没有 #42" in missing.output)
    }

    @Test
    fun `stop keeps the record, only what is still scheduled`() = runBlocking {
        val d = desk()
        val out = StopRemindersTool(d).execute("""{"ids":[3,9]}""", ctx)
        assertFalse("开会已经完成了，不算停了", out.ok)
        assertEquals("停了 1 条：喝水", out.output.lines().first())
        assertTrue(out.output, "#9" in out.output.lines().last())
        assertEquals(ReminderStatus.CANCELLED, d.reminders.single { it.id == 3L }.status)
        assertEquals(4, d.reminders.size)

        val none = StopRemindersTool(d).execute("""{"ids":[3]}""", ctx)
        assertFalse(none.ok)
        assertTrue(none.output.startsWith("没停。"))
    }

    @Test
    fun `delete removes what exists and says what did not`() = runBlocking {
        val d = desk()
        val out = DeleteRemindersTool(d).execute("""{"ids":[3,5]}""", ctx)
        assertTrue(out.ok)
        assertEquals("删了 2 条：喝水；交房租", out.output.lines().first())
        assertEquals(listOf(7L, 9L), d.reminders.map { it.id })

        val partly = DeleteRemindersTool(d).execute("""{"ids":[7,3]}""", ctx)
        assertFalse(partly.ok)
        assertTrue(partly.output, "没有这几条：#3" in partly.output)

        val none = DeleteRemindersTool(d).execute("""{"ids":[100]}""", ctx)
        assertFalse(none.ok)
        assertTrue(none.output.startsWith("没删。"))
    }

    @Test
    fun `traces for update and delete`() {
        val updated = ReminderTrace.of(ToolTrace(UpdateReminderTool.NAME, plan(5, "2026-11-20"), TraceState.OK, "", 5L))!!
        assertTrue(updated.text, updated.text.startsWith("改成 ") && updated.text.endsWith(" · 交房租"))
        assertEquals("提醒", updated.settled)
        assertTrue(updated.route != null)

        val refused = ReminderTrace.of(
            ToolTrace(UpdateReminderTool.NAME, "{}", TraceState.FAILED, "没改。没有 #42 这条提醒，先用 list_reminders 查一下编号。", null)
        )!!
        assertEquals("没改成", refused.settled)
        assertEquals("没有 #42 这条提醒，先用 list_reminders 查一下编号", refused.text)

        val one = ReminderTrace.of(ToolTrace(DeleteRemindersTool.NAME, "{}", TraceState.OK, "删了 1 条：喝水\n#3 喝水", null))!!
        assertEquals("删了 · 喝水", one.text)
        assertNull(one.route)
        val two = ReminderTrace.of(ToolTrace(DeleteRemindersTool.NAME, "{}", TraceState.OK, "删了 2 条：喝水；交房租\n…", null))!!
        assertEquals("删了 2 条", two.text)
        assertEquals(listOf("删了 · 喝水", "删了 · 交房租"), two.lines)

        val stopped = ReminderTrace.of(ToolTrace(StopRemindersTool.NAME, "{}", TraceState.OK, "停了 1 条：吃药\n#6 吃药", null))!!
        assertEquals("不再提醒 · 吃药", stopped.text)
        assertNull(stopped.route)

        assertNull("查的不留痕", ReminderTrace.of(ToolTrace(ListRemindersTool.NAME, "{}", TraceState.OK, "", null)))
    }
}
