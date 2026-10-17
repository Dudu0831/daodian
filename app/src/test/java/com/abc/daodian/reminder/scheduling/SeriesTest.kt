package com.abc.daodian.reminder.scheduling

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 重复的定时提醒响过之后的「完成」「稍后」：只管刚响的这一次，这一串照常往下走 */
class SeriesTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(s: String) = ZonedDateTime.parse("${s}+08:00[Asia/Shanghai]")
    private fun ms(s: String) = at(s).toInstant().toEpochMilli()

    private fun daily(next: String, seriesNext: String? = null, status: ReminderStatus = ReminderStatus.SCHEDULED) = Reminder(
        title = "吃药", nextTriggerAt = ms(next), rrule = "FREQ=DAILY", zoneId = zone.id,
        localTime = "17:00", wallClockAnchored = true, status = status,
        seriesNextAt = seriesNext?.let { ms(it) }, createdAt = 0, updatedAt = 0
    )

    @Test
    fun `normal fire walks to the next occurrence`() {
        val r = daily("2026-10-16T17:00:00")
        assertEquals(at("2026-10-17T17:00:00"), Series.nextAfterFire(r, ms("2026-10-16T17:00:00"), zone))
    }

    @Test
    fun `snoozed fire goes back to the series instead of drifting by the snooze`() {
        // 17:00 响过、排到了明天 17:00，点「稍后」：这次挪到 17:10，明天 17:00 记下来
        val r = daily("2026-10-16T17:10:00", seriesNext = "2026-10-17T17:00:00")
        assertEquals(at("2026-10-17T17:00:00"), Series.nextAfterFire(r, ms("2026-10-16T17:10:00"), zone))
    }

    @Test
    fun `snooze across midnight does not skip the next day`() {
        val r = daily("2026-10-17T00:05:00", seriesNext = "2026-10-17T23:55:00").copy(localTime = "23:55")
        assertEquals(at("2026-10-17T23:55:00"), Series.nextAfterFire(r, ms("2026-10-17T00:05:00"), zone))
    }

    @Test
    fun `snooze that fires after the series next pushes on to a future occurrence`() {
        // 补发迟到了：稍后那次到第二天 17:30 才响，记下的 17:00 已经过了
        val r = daily("2026-10-16T17:10:00", seriesNext = "2026-10-17T17:00:00")
        assertEquals(at("2026-10-18T17:00:00"), Series.nextAfterFire(r, ms("2026-10-17T17:30:00"), zone))
    }

    @Test
    fun `weekly by day returns to the right weekday`() {
        // 每周二、四 17:00；周二那次稍后到 17:10，记下的是周四
        val r = daily("2026-10-20T17:10:00", seriesNext = "2026-10-22T17:00:00").copy(rrule = "FREQ=WEEKLY;BYDAY=TU,TH")
        assertEquals(at("2026-10-22T17:00:00"), Series.nextAfterFire(r, ms("2026-10-20T17:10:00"), zone))
    }

    @Test
    fun `snooze remembers where the series was`() {
        // 响过了，已经排到明天（稍后那次响完也是回到这样）：稍后记下明天那一刻
        assertEquals(ms("2026-10-17T17:00:00"), Series.seriesNextOnSnooze(daily("2026-10-17T17:00:00")))
        // 稍后着又点一次（全屏页和通知各点一下）：沿用记下的，不拿稍后那一刻
        assertEquals(
            ms("2026-10-17T17:00:00"),
            Series.seriesNextOnSnooze(daily("2026-10-16T17:10:00", seriesNext = "2026-10-17T17:00:00"))
        )
    }

    @Test
    fun `only live repeating timed reminders are handled as a series`() {
        assertTrue(Series.isLive(daily("2026-10-17T17:00:00")))
        // 一串到头了（UNTIL 过了），FireHandler 落成 FIRED：照一次性的办
        assertFalse(Series.isLive(daily("2026-10-16T17:00:00", status = ReminderStatus.FIRED)))
        assertNull(Series.seriesNextOnSnooze(daily("2026-10-16T17:00:00", status = ReminderStatus.FIRED)))
        // 一次性的、超出子集的规则（FireHandler 也当一次性的）
        assertFalse(Series.isLive(daily("2026-10-17T17:00:00").copy(rrule = null)))
        assertFalse(Series.isLive(daily("2026-10-17T17:00:00").copy(rrule = "FREQ=HOURLY")))
        // 当天事项有自己的完成（DayTasks.complete）
        assertFalse(Series.isLive(daily("2026-10-17T20:00:00").copy(dueDay = "2026-10-17")))
    }

    @Test
    fun `series end returns null`() {
        val r = daily("2026-10-16T17:10:00", seriesNext = "2026-10-17T17:00:00")
            .copy(rrule = "FREQ=DAILY;UNTIL=2026-10-17T18:00:00+08:00")
        // 记下的那一刻还在 UNTIL 之内：照常回去
        assertEquals(at("2026-10-17T17:00:00"), Series.nextAfterFire(r, ms("2026-10-16T17:10:00"), zone))
        // 稍后那次迟到过了它，再往后就出了 UNTIL：到头了
        assertNull(Series.nextAfterFire(r, ms("2026-10-17T17:30:00"), zone))
    }
}
