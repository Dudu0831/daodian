package com.abc.daodian.reminder.card

import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.card.plugin.CardRow
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderItem
import com.abc.daodian.reminder.data.ReminderStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提醒交给桌面卡片的那一份（DESIGN §8.5）：一条提醒说的是哪一天、还剩几天、办完没有、清单、几点、下一次响的那一刻 */
class ReminderCardsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.parse("2026-11-15")

    private val milk = Reminder(
        id = 7, title = "牛奶过期", nextTriggerAt = ZonedDateTime.of(2026, 12, 13, 20, 0, 0, 0, zone).toInstant().toEpochMilli(),
        zoneId = zone.id, day = LocalDate.parse("2027-02-13"), phase = Phase.NUDGE, remindBefore = "62,31", ring = Ring.NOTICE, nag = Nag.DAILY, createdAt = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), updatedAt = 0
    )

    private fun f(name: String, r: Reminder = milk, args: List<String> = listOf("#7"), on: LocalDate = today, items: List<ReminderItem> = emptyList()) =
        ReminderCards.field(r, name, args, on, zone, items)

    @Test
    fun `a day item counts to its own day, even while it is being nudged`() {
        assertEquals(LocalDate.parse("2027-02-13"), ReminderCards.dayOf(milk))
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
    fun `a timed one is the day of this time, and a finished one is done`() {
        val rent = Reminder(id = 8, title = "交房租", nextTriggerAt = ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), zoneId = zone.id, day = LocalDate.parse("2026-11-20"), times = "09:00", slot = "09:00", createdAt = 0, updatedAt = 0)
        assertEquals(CardValue.Number(5), f("days_left", r = rent))
        assertEquals(CardValue.Flag(true), f("done", r = rent.copy(status = ReminderStatus.DONE)))
    }

    @Test
    fun `the checklist comes as rows that tick themselves, plus counts`() {
        val items = listOf("护照", "插头", "外套").mapIndexed { i, t -> ReminderItem(id = 40L + i, reminderId = 7, text = t, done = i == 1, position = i) }
        assertEquals(
            CardValue.Rows(listOf(
                CardRow("护照", action = "reminder.check #7 1"),
                CardRow("插头", done = true, action = "reminder.check #7 2"),
                CardRow("外套", action = "reminder.check #7 3")
            )),
            f("items", items = items)
        )
        assertEquals(CardValue.Number(2), f("items_left", items = items))
        assertEquals(CardValue.Number(1), f("items_checked", items = items))
        assertEquals(CardValue.Number(3), f("items_total", items = items))
        assertEquals(CardValue.Flag(false), f("items_done", items = items))
        assertEquals(CardValue.Flag(true), f("items_done", items = items.map { it.copy(done = true) }))
        // 没有清单不算勾完
        assertEquals(CardValue.Flag(false), f("items_done"))
    }

    @Test
    fun `time, repeat, the next nudge and the moment it rings`() {
        assertEquals(CardValue.Text("当天"), f("time"))
        assertEquals(CardValue.Text("12 月 13 日"), f("next_nudge"))
        assertEquals(CardValue.Text(""), f("repeat"))
        assertEquals(CardValue.Moment(milk.nextTriggerAt, "20:00"), f("fire_at"))
        val rent = milk.copy(times = "09:00", slot = "09:00", rrule = "FREQ=MONTHLY;BYMONTHDAY=15", phase = Phase.MAIN)
        assertEquals(CardValue.Text("09:00"), f("time", r = rent))
        assertEquals(CardValue.Text(""), f("next_nudge", r = rent))
        assertTrue((f("repeat", r = rent) as CardValue.Text).text.contains("15"))
        // 点完成了的不会再响，走秒没得数
        assertEquals(null, f("fire_at", r = milk.copy(status = ReminderStatus.DONE)))
    }
}
