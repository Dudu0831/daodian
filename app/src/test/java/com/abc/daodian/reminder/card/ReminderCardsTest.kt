package com.abc.daodian.reminder.card

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提醒交给桌面卡片的那一份（DESIGN §8.5）：一条提醒说的是哪一天、办完没有 */
class ReminderCardsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `a day item is its own day, even while it is being carried over or nudged`() {
        val at = ZonedDateTime.of(2026, 12, 13, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val milk = Reminder(title = "牛奶过期", nextTriggerAt = at, zoneId = zone.id, dueDay = "2027-02-13", createdAt = 0, updatedAt = 0)
        val s = ReminderCards.subjectOf(milk, zone)
        assertEquals("牛奶过期", s.title)
        assertEquals(LocalDate.parse("2027-02-13"), s.day)
        assertFalse(s.done)
    }

    @Test
    fun `a timed one is the day it next rings, and a finished one is done`() {
        val at = ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val rent = Reminder(title = "交房租", nextTriggerAt = at, zoneId = zone.id, createdAt = 0, updatedAt = 0)
        assertEquals(LocalDate.parse("2026-11-20"), ReminderCards.subjectOf(rent, zone).day)
        assertTrue(ReminderCards.subjectOf(rent.copy(status = ReminderStatus.DONE), zone).done)
    }
}
