package com.abc.daodian.reminder.card

import com.abc.daodian.card.assistant.CardGuide
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.source.TimeSource
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提醒交给桌面卡片的那一份（DESIGN §8.5）：一条提醒说的是哪一天、还剩几天、办完没有 */
class ReminderCardsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.parse("2026-11-15")

    private val milk = Reminder(
        id = 7, title = "牛奶过期", nextTriggerAt = ZonedDateTime.of(2026, 12, 13, 20, 0, 0, 0, zone).toInstant().toEpochMilli(),
        zoneId = zone.id, dueDay = "2027-02-13", createdAt = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), updatedAt = 0
    )

    private fun f(name: String, r: Reminder = milk, args: List<String> = listOf("#7"), on: LocalDate = today) =
        ReminderCards.field(r, name, args, on, zone)

    @Test
    fun `a day item counts to its own day, even while it is being nudged`() {
        assertEquals(LocalDate.parse("2027-02-13"), ReminderCards.dayOf(milk, zone))
        assertEquals(CardValue.Number(90), f("days_left"))
        assertEquals(CardValue.Text("2027 年 2 月 13 日"), f("date"))
        assertEquals(CardValue.Text("周六"), f("weekday"))
        assertEquals(CardValue.Flag(false), f("near"))
        assertEquals(CardValue.Flag(true), f("near", on = LocalDate.parse("2027-02-08")))
        assertEquals(CardValue.Flag(true), f("is_today", on = LocalDate.parse("2027-02-13")))
        assertEquals(CardValue.Number(2), f("days_past", on = LocalDate.parse("2027-02-15")))
        assertEquals(CardValue.Number(0), f("days_left", on = LocalDate.parse("2027-02-15")))
    }

    @Test
    fun `progress runs from when it was made or from the given start`() {
        assertEquals(CardValue.Number(2), f("progress"))
        assertEquals(CardValue.Number(50), f("progress", args = listOf("#7", "2026-08-17")))
    }

    @Test
    fun `a timed one is the day it next rings, and a finished one is done`() {
        val rent = Reminder(id = 8, title = "交房租", nextTriggerAt = ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), zoneId = zone.id, createdAt = 0, updatedAt = 0)
        assertEquals(CardValue.Number(5), f("days_left", r = rent))
        assertEquals(CardValue.Flag(true), f("done", r = rent.copy(status = ReminderStatus.DONE)))
    }

    @Test
    fun `the guide's countdown example passes the gate with the real reminder fields`() {
        val vocab = CardSpecs.Vocabulary(listOf(ReminderCards, TimeSource()))
        assertTrue(CardSpecs.check(CardGuide.COUNTDOWN, vocab) is CardSpecs.Result.Ok)
        assertTrue(CardSpecs.check(CardGuide.WATER, vocab) is CardSpecs.Result.Ok)
        val list = """{"name":"今天","size":"4x2","body":{"column":[{"text":"今天","style":"title"},{"list":"{{reminder.today}}","max":3},{"text":"下一件 · {{reminder.next_in}}","style":"caption"}]}}"""
        assertTrue(CardSpecs.check(list, vocab).toString(), CardSpecs.check(list, vocab) is CardSpecs.Result.Ok)
    }
}
