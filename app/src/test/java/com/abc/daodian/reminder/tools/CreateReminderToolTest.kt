package com.abc.daodian.reminder.tools

import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.runtime.tool.ToolContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** create_reminder：六件事从参数到落库那一份、回给模型的那句、对话里的痕（§4.6） */
class CreateReminderToolTest {

    private val now = ZonedDateTime.parse("2026-11-16T11:20:00+08:00[Asia/Shanghai]")

    private fun args(
        day: String = "2026-11-16", times: String = "", rrule: String? = null, before: String = "",
        ring: String = "alarm", nag: String = "none", title: String = "吃药"
    ) = """{"title":"$title","day":"$day","times":[$times],"basis":"今天","note":null,"rrule":${rrule?.let { "\"$it\"" }},""" +
        """"remindBefore":[$before],"ring":"$ring","nag":"$nag","wallClockAnchored":true}"""

    private suspend fun create(arguments: String): Pair<Boolean, ReminderSpec?> {
        var got: ReminderSpec? = null
        val out = CreateReminderTool({ spec, _, _ -> got = spec; 7L }).execute(arguments, ToolContext(now, ""))
        return out.ok to got
    }

    @Test
    fun `several times a day, repeating, nagging — all on one reminder`() = runBlocking {
        val (ok, spec) = create(args(times = "\"15:00\",\"09:00\"", rrule = "FREQ=DAILY", nag = "soon"))
        assertTrue(ok)
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(15, 0)), spec!!.times)
        assertEquals(Nag.SOON, spec.nag)
        assertEquals(Ring.ALARM, spec.ring)
    }

    @Test
    fun `an untimed one repeats and a timed one gets reminded ahead`() = runBlocking {
        // 以前设不出来的两种：没钟点的每天背单词、有钟点的飞机提前一周
        val words = create(args(rrule = "FREQ=DAILY", ring = "notice", title = "背单词")).second!!
        assertEquals(emptyList<LocalTime>(), words.times)
        assertEquals("FREQ=DAILY", words.rrule)
        val flight = create(args(day = "2026-12-23", times = "\"07:30\"", before = "7,1", title = "飞旧金山")).second!!
        assertEquals(listOf(7, 1), flight.remindBefore)
        assertEquals(LocalDate.parse("2026-12-23"), flight.day)
    }

    @Test
    fun `the model hears back all six things`() = runBlocking {
        val out = CreateReminderTool({ _, _, _ -> 7L })
            .execute(args(day = "2027-02-13", before = "30,1", ring = "notice", nag = "daily", title = "牛奶过期"), ToolContext(now, ""))
        assertEquals(
            "已建好（id=7）：「牛奶过期」，2027-02-13 不定钟点（那天晚上收尾时刻提醒），不重复，提前 30、1 天提一下，只发通知，没点完成每晚再提。",
            out.output
        )
    }

    @Test
    fun `missing ring or nag is not a plan`() = runBlocking {
        val out = CreateReminderTool({ _, _, _ -> 7L }).execute("""{"title":"喝水","day":"2026-11-16","times":[]}""", ToolContext(now, ""))
        assertFalse(out.ok)
        assertTrue(out.output.startsWith("没建。参数不是合法的 JSON 或缺字段"))
    }

    @Test
    fun `trace says when, gentle and nudges before the title`() {
        fun text(arguments: String) = ReminderTrace.of(ToolTrace(CreateReminderTool.NAME, arguments, TraceState.OK, null, 7L))!!.text
        assertEquals("每天 09:00、15:00 · 吃药", text(args(times = "\"09:00\",\"15:00\"", rrule = "FREQ=DAILY")))
        assertEquals("每天 14:00 · 轻提醒 · 喝水", text(args(times = "\"14:00\"", rrule = "FREQ=DAILY", ring = "notice", title = "喝水")))
        // 没钟点的本来就只发通知，不写轻提醒
        assertEquals("每周六 · 当天 · 买菜", text(args(day = "2026-11-21", rrule = "FREQ=WEEKLY;BYDAY=SA", ring = "notice", title = "买菜")))
        assertTrue(text(args(day = "2026-12-23", times = "\"07:30\"", before = "7,1")).endsWith(" · 提前提醒 2 次 · 吃药"))
        // 11-16 以前的旧参数：只写标题
        assertEquals("带伞", text("""{"title":"带伞","firstTriggerAt":"2026-11-01T08:00:00+08:00","allDay":false}"""))
    }

    @Test
    fun `basis reads from old and new arguments alike`() {
        assertEquals("今天", CreateReminderTool.basisOf(args()))
        assertEquals("明天 8 点", CreateReminderTool.basisOf("""{"title":"带伞","firstTriggerAt":"x","basis":"明天 8 点"}"""))
        assertNull(CreateReminderTool.basisOf("""{"title":"带伞"}"""))
    }
}
