package com.abc.daodian.reminder.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 每月规则：正数、负数（从月底倒数）、31 号落在小月 */
class RruleTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(s: String) = ZonedDateTime.parse("${s}T20:00:00+08:00[Asia/Shanghai]")

    @Test
    fun `last day of month walks month ends`() {
        val rule = "FREQ=MONTHLY;BYMONTHDAY=-1"
        assertEquals(at("2026-10-31"), Rrule.nextAfter(rule, at("2026-09-30"), zone))
        assertEquals(at("2026-11-30"), Rrule.nextAfter(rule, at("2026-10-31"), zone))
        assertEquals(at("2027-02-28"), Rrule.nextAfter(rule, at("2027-01-31"), zone))
    }

    @Test
    fun `second to last day counts back from the end`() {
        assertEquals(at("2026-10-30"), Rrule.nextAfter("FREQ=MONTHLY;BYMONTHDAY=-2", at("2026-09-29"), zone))
    }

    @Test
    fun `31st falls back to the last day of a short month`() {
        assertEquals(at("2026-09-30"), Rrule.nextAfter("FREQ=MONTHLY;BYMONTHDAY=31", at("2026-08-31"), zone))
    }

    @Test
    fun `out of range month day is treated as unset`() {
        assertEquals(at("2026-10-15"), Rrule.nextAfter("FREQ=MONTHLY;BYMONTHDAY=0", at("2026-09-15"), zone))
    }

    @Test
    fun `first day on or after lands on the rule`() {
        fun d(s: String) = LocalDate.parse(s)
        // 11-16 是周一
        assertEquals(d("2026-11-21"), Rrule.firstOnOrAfter("FREQ=WEEKLY;BYDAY=SA", d("2026-11-16")))
        assertEquals(d("2026-11-16"), Rrule.firstOnOrAfter("FREQ=WEEKLY;BYDAY=MO,FR", d("2026-11-16")))
        assertEquals(d("2026-12-05"), Rrule.firstOnOrAfter("FREQ=MONTHLY;BYMONTHDAY=5", d("2026-11-16")))
        assertEquals(d("2026-11-30"), Rrule.firstOnOrAfter("FREQ=MONTHLY;BYMONTHDAY=31", d("2026-11-16")))
        assertEquals(d("2027-02-28"), Rrule.firstOnOrAfter("FREQ=MONTHLY;BYMONTHDAY=-1", d("2027-02-01")))
        assertEquals(d("2026-11-16"), Rrule.firstOnOrAfter("FREQ=DAILY", d("2026-11-16")))
        assertEquals(d("2026-11-16"), Rrule.firstOnOrAfter(null, d("2026-11-16")))
    }
}
