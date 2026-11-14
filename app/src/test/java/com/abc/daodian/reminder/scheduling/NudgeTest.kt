package com.abc.daodian.reminder.scheduling

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.nudgeDates
import com.abc.daodian.reminder.data.nudgesColumn
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 日子还远的当天事项的提前提醒（DESIGN §4.4）：一条记录，响完翻到下一次，到了那天才是普通的当天事项 */
class NudgeTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val check = LocalTime.of(20, 0)
    private fun at(s: String) = ZonedDateTime.parse("${s}+08:00[Asia/Shanghai]")
    private fun ms(s: String) = at(s).toInstant().toEpochMilli()
    private fun day(s: String) = LocalDate.parse(s)

    private val due = day("2027-02-13")
    private val nudges = listOf("2026-12-13", "2027-01-13", "2027-02-11", "2027-02-12").map(::day)

    private fun milk(next: String) = Reminder(
        title = "牛奶过期", nextTriggerAt = ms(next), zoneId = zone.id, localTime = "20:00", wallClockAnchored = true,
        dueDay = due.toString(), nudges = nudgesColumn(nudges), createdAt = 0, updatedAt = 0
    )

    @Test
    fun `first trigger is the nearest nudge that has not passed`() {
        assertEquals(ms("2026-12-13T20:00:00"), DayTasks.nextTrigger(due, nudges, check, zone, ms("2026-11-13T10:00:00")))
        // 当天的收尾时刻已经过了：这一次不补，直接下一个
        assertEquals(ms("2027-01-13T20:00:00"), DayTasks.nextTrigger(due, nudges, check, zone, ms("2026-12-13T21:00:00")))
    }

    @Test
    fun `no nudges left means the day itself, and without nudges it is the old rule`() {
        assertEquals(ms("2027-02-13T20:00:00"), DayTasks.nextTrigger(due, nudges, check, zone, ms("2027-02-12T20:00:01")))
        val now = ms("2026-11-13T10:00:00")
        assertEquals(DayTasks.triggerFor(due, check, zone, now), DayTasks.nextTrigger(due, emptyList(), check, zone, now))
    }

    @Test
    fun `a nudge on or after the day is ignored`() {
        val odd = nudges + day("2027-02-13") + day("2027-03-01")
        assertEquals(ms("2027-02-13T20:00:00"), DayTasks.nextTrigger(due, odd, check, zone, ms("2027-02-12T20:00:01")))
    }

    @Test
    fun `after a nudge fires it walks to the next one and then to the day`() {
        var r = milk("2026-12-13T20:00:00")
        assertTrue(DayTasks.isNudge(r, zone))
        val seen = mutableListOf<Long>()
        while (true) {
            val next = DayTasks.afterNudgeAt(r, r.nextTriggerAt + 500, zone) ?: break
            seen += next
            r = r.copy(nextTriggerAt = next)
        }
        assertEquals(
            listOf("2027-01-13T20:00:00", "2027-02-11T20:00:00", "2027-02-12T20:00:00", "2027-02-13T20:00:00").map(::ms),
            seen
        )
        // 到了那天：不再是提前提醒，交回当天事项原来的规矩（顺延或结束）
        assertFalse(DayTasks.isNudge(r, zone))
        assertNull(DayTasks.afterNudgeAt(r, r.nextTriggerAt + 500, zone))
    }

    @Test
    fun `a late catch-up skips the nudges that were missed`() {
        // 12-13 那次一直没响，1 月 20 日才补发：1-13 那次不再补，直接排 2-11
        val r = milk("2026-12-13T20:00:00")
        assertEquals(ms("2027-02-11T20:00:00"), DayTasks.afterNudgeAt(r, ms("2027-01-20T09:00:00"), zone))
        // 连那天的钟点都过了：不归提前提醒管
        assertNull(DayTasks.afterNudgeAt(r, ms("2027-02-13T20:00:01"), zone))
    }

    @Test
    fun `the clock of the nudge is kept, not re-read`() {
        // 收尾时刻改成过 21:30 的那一条，之后几次都跟着 21:30
        val r = milk("2026-12-13T21:30:00")
        assertEquals(ms("2027-01-13T21:30:00"), DayTasks.afterNudgeAt(r, ms("2026-12-13T21:30:01"), zone))
    }

    @Test
    fun `column round trip sorts and drops duplicates`() {
        val col = nudgesColumn(listOf(day("2027-01-13"), day("2026-12-13"), day("2027-01-13")))
        assertEquals("2026-12-13,2027-01-13", col)
        assertEquals(listOf(day("2026-12-13"), day("2027-01-13")), milk("2026-12-13T20:00:00").copy(nudges = col).nudgeDates())
        assertNull(nudgesColumn(emptyList()))
    }

    // ---- 闸门

    private val now = at("2026-11-13T10:00:00")
    private fun plan(remindOn: List<String>, allDay: Boolean = true, rrule: String? = null) = ReminderPlan(
        title = "牛奶过期", firstTriggerAt = if (allDay) "2027-02-13T00:00:00+08:00" else "2027-02-13T09:00:00+08:00",
        basis = "now + 3 个月", allDay = allDay, rrule = rrule, remindOn = remindOn
    )

    @Test
    fun `gate keeps the days before the day, drops today-and-earlier leftovers and the day itself`() {
        val v = PlanValidator.validate(plan(listOf("2027-02-13", "2027-01-13", "2026-11-01", "2026-12-13", "2026-12-13")), now)
        assertEquals(listOf("2026-12-13", "2027-01-13"), (v as Verdict.Ok).plan.remindOn)
    }

    @Test
    fun `gate refuses nudges after the day, on timed or repeating reminders, and garbage`() {
        assertTrue(PlanValidator.validate(plan(listOf("2027-03-01")), now) is Verdict.Rejected)
        assertTrue(PlanValidator.validate(plan(listOf("2026-12-13"), allDay = false), now) is Verdict.Rejected)
        assertTrue(PlanValidator.validate(plan(listOf("2026-12-13"), rrule = "FREQ=YEARLY"), now) is Verdict.Rejected)
        assertTrue(PlanValidator.validate(plan(listOf("12月13日")), now) is Verdict.Rejected)
        assertTrue(PlanValidator.validate(plan(emptyList(), allDay = false), now) is Verdict.Ok)
    }
}
