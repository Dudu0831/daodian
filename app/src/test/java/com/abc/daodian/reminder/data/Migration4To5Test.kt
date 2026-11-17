package com.abc.daodian.reminder.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** v4 → v5 一行怎么换（[convert]）：排期那一刻原样留着，只把「为什么排在那一刻」写成新字段 */
class Migration4To5Test {

    private val here = ZoneId.of("Asia/Shanghai")

    private fun ms(local: String, z: ZoneId = here) = ZonedDateTime.of(LocalDateTime.parse(local), z).toInstant().toEpochMilli()

    private fun v4(
        next: String,
        rrule: String? = null,
        zoneId: String = here.id,
        localTime: String? = null,
        anchored: Boolean = false,
        dueDay: String? = null,
        gentle: Boolean = false,
        seriesNext: String? = null,
        nudges: String? = null,
        endsOnDay: Boolean = false
    ) = V4Row(ms(next, ZoneId.of(zoneId)), rrule, zoneId, localTime, anchored, dueDay, gentle, seriesNext?.let { ms(it) }, nudges, endsOnDay)

    @Test
    fun `a carried errand becomes the same day nagging every night`() {
        val v5 = convert(v4("2026-11-13T20:00:00", dueDay = "2026-11-11", localTime = "20:00", anchored = true), here)
        assertEquals(ms("2026-11-13T20:00:00"), v5.nextTriggerAt)
        assertEquals(LocalDate.parse("2026-11-11"), v5.day)
        assertEquals(null, v5.times)
        assertEquals(Ring.NOTICE, v5.ring)
        assertEquals(Nag.DAILY, v5.nag)
        assertEquals(Phase.NAG, v5.phase)
        assertEquals(2, v5.nagCount)
    }

    @Test
    fun `nudge dates become days before, ending on the day becomes no nagging`() {
        val v5 = convert(
            v4("2026-12-13T20:00:00", dueDay = "2027-02-13", nudges = "2026-12-13,2027-01-13", endsOnDay = true, anchored = true),
            here
        )
        assertEquals(Phase.NUDGE, v5.phase)
        assertEquals("62,31", v5.remindBefore)
        assertEquals(Nag.NONE, v5.nag)
        assertEquals(ms("2026-12-13T20:00:00"), v5.nextTriggerAt)
    }

    @Test
    fun `a repeating untimed one does not nag and has no nudges`() {
        val v5 = convert(v4("2026-11-21T20:00:00", rrule = "FREQ=WEEKLY;BYDAY=SA", dueDay = "2026-11-21", nudges = "2026-11-20"), here)
        assertEquals(Phase.MAIN, v5.phase)
        assertEquals(Nag.NONE, v5.nag)
        assertEquals(null, v5.remindBefore)
    }

    @Test
    fun `a timed one keeps its clock, gentle becomes notice only`() {
        val v5 = convert(v4("2026-11-14T10:00:00", rrule = "FREQ=DAILY", localTime = "10:00", anchored = true, gentle = true), here)
        assertEquals(LocalDate.parse("2026-11-14"), v5.day)
        assertEquals("10:00", v5.times)
        assertEquals("10:00", v5.slot)
        assertEquals(Ring.NOTICE, v5.ring)
        assertEquals(Nag.NONE, v5.nag)
        assertEquals(Phase.MAIN, v5.phase)
    }

    @Test
    fun `a snoozed repeating one goes back to where the series was`() {
        val v5 = convert(
            v4("2026-11-16T17:10:00", rrule = "FREQ=DAILY", localTime = "17:00", anchored = true, seriesNext = "2026-11-17T17:00:00"),
            here
        )
        assertEquals(ms("2026-11-17T17:00:00"), v5.nextTriggerAt)
        assertEquals(LocalDate.parse("2026-11-17"), v5.day)
        assertEquals("17:00", v5.slot)
    }

    @Test
    fun `a fixed meeting set elsewhere keeps the clock of where it was set`() {
        val la = ZoneId.of("America/Los_Angeles")
        val v5 = convert(v4("2026-11-20T09:00:00", zoneId = la.id), here)
        assertEquals(LocalDate.parse("2026-11-20"), v5.day)
        assertEquals("09:00", v5.slot)
        assertEquals(ms("2026-11-20T09:00:00", la), v5.nextTriggerAt)
        assertEquals(false, v5.wallClockAnchored)
    }
}
