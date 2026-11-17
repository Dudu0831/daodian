package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Ring
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模型的参数（还是两种提醒那一套）换成六件事（§4.6） */
class ReminderSpecTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `a far untimed one keeps its day, nudge dates become days before`() {
        val spec = ReminderPlan(
            title = "牛奶过期", firstTriggerAt = "2027-02-13T00:00:00+08:00", allDay = true,
            remindOn = listOf("2026-12-13", "2027-02-12"), carryOver = true
        ).toSpec(zone)
        assertEquals(LocalDate.parse("2027-02-13"), spec.day)
        assertEquals(emptyList<LocalTime>(), spec.times)
        assertEquals(listOf(62, 1), spec.remindBefore)
        assertEquals(Ring.NOTICE, spec.ring)
        assertEquals(Nag.DAILY, spec.nag)
    }

    @Test
    fun `just a date does not nag, a repeating untimed one has no nudges`() {
        val spring = ReminderPlan(title = "春节", firstTriggerAt = "2027-02-06T00:00:00+08:00", allDay = true, carryOver = false).toSpec(zone)
        assertEquals(Nag.NONE, spring.nag)
        val shop = ReminderPlan(
            title = "去超市", firstTriggerAt = "2026-11-21T00:00:00+08:00", allDay = true, rrule = "FREQ=WEEKLY;BYDAY=SA",
            remindOn = listOf("2026-11-20")
        ).toSpec(zone)
        assertEquals(emptyList<Int>(), shop.remindBefore)
        assertEquals(Nag.NONE, shop.nag)
    }

    @Test
    fun `a timed one is its local day and clock, gentle rings as a notice`() {
        val spec = ReminderPlan(title = "喝水", firstTriggerAt = "2026-11-14T10:00:30+08:00", rrule = "FREQ=DAILY", gentle = true).toSpec(zone)
        assertEquals(LocalDate.parse("2026-11-14"), spec.day)
        assertEquals(listOf(LocalTime.of(10, 0)), spec.times)
        assertEquals(Ring.NOTICE, spec.ring)
        assertEquals(Nag.NONE, spec.nag)
        assertEquals(Ring.ALARM, ReminderPlan(title = "开会", firstTriggerAt = "2026-11-17T09:00:00+08:00").toSpec(zone).ring)
    }
}
