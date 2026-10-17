package com.abc.daodian.reminder.scheduling

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.isAllDay
import com.abc.daodian.reminder.domain.Rrule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 重复的定时提醒响过之后，通知和全屏页上的「完成」「稍后」。见 DESIGN.md §5.6
 *
 * 响的那一刻 [FireHandler] 已经把这条排到了下一次（保持 SCHEDULED），所以这两个按钮管的只是刚响的这一次：
 *  - 「完成」只收通知，不碰排期。以前照一次性的改成 DONE，整串就停了，明天不再响
 *  - 「稍后」只挪这一次：这一串本来的下一次记在 [Reminder.seriesNextAt]，稍后那次响完回到它（[nextAfterFire]）。
 *    以前直接改 nextTriggerAt，下一次从稍后那一刻往后算，从此每次晚 10 分钟
 *
 * 当天事项不归这里（[DayTasks.complete]）；列表的圈、小组件的 ✓ 也不归这里，照旧整条停掉。
 */
object Series {

    /** 还挂着的重复定时提醒。一串到头了的已经是 FIRED，照一次性的办 */
    fun isLive(r: Reminder): Boolean =
        !r.isAllDay && r.status == ReminderStatus.SCHEDULED && Rrule.isSupported(r.rrule)

    /** 稍后时要记下的「这一串本来的下一次」；null = 不是重复的，稍后只是挪这一次。已经在稍后的，沿用记下的那一刻 */
    fun seriesNextOnSnooze(r: Reminder): Long? = if (isLive(r)) r.seriesNextAt ?: r.nextTriggerAt else null

    /**
     * 这一次响完（或稍后着点了「完成」）之后排到哪一刻；null = 一次性的，或者一串到头了。
     *
     * 稍后那一次：从记下的这一串本来的下一次接着走；平常那一次：从这一次往后算。
     * 补发场景下算出来的「下次」可能仍在过去，一路推到 [firedAt] 之后为止。
     */
    fun nextAfterFire(r: Reminder, firedAt: Long, zone: ZoneId): ZonedDateTime? {
        val pending = r.seriesNextAt
        var candidate = if (pending != null) {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(pending), zone)
        } else {
            Rrule.nextAfter(r.rrule, ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.nextTriggerAt), zone), zone)
        }
        var guard = 0
        while (candidate != null && candidate.toInstant().toEpochMilli() <= firedAt && guard < 1000) {
            candidate = Rrule.nextAfter(r.rrule, candidate, zone)
            guard++
        }
        return candidate
    }
}
