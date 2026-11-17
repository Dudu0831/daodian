package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Ring
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 一条提醒要定的那几件事（DESIGN.md §4.6）：哪天、几点（空 = 不定）、重复、提前几天、怎么响、要不要催。
 * 建、改都交这一份，手动的和模型的一样；「这一次排到哪了」由 `Reminders` 照它算
 */
data class ReminderSpec(
    val title: String,
    val note: String? = null,
    val day: LocalDate,
    val times: List<LocalTime> = emptyList(),
    val rrule: String? = null,
    val remindBefore: List<Int> = emptyList(),
    val ring: Ring = Ring.ALARM,
    val nag: Nag = Nag.NONE,
    /** 有钟点的才看：true = 跟着人走的当地钟点，false = 固定那一瞬间（§7.1） */
    val wallClockAnchored: Boolean = true
)

/**
 * 模型给的一条换成 [ReminderSpec]。模型的参数还是两种提醒那一套（`allDay` / `gentle` / `remindOn` / `carryOver`），
 * 改参数是第三步（§4.6），在那之前在这里换算：
 * - 当天事项：只取日期（模型带的偏移就是用户的，不换算，换算会把 00:00 挪到前一天）、不定钟点、只发通知；
 *   不重复的：提前提醒的日期换成提前几天，`carryOver` → 每天再提，不然不催
 * - 定时的：[zone] 下的那天那个钟点，`gentle` → 只发通知，不催
 */
fun ReminderPlan.toSpec(zone: ZoneId): ReminderSpec {
    val at = OffsetDateTime.parse(firstTriggerAt)
    if (allDay) {
        val day = at.toLocalDate()
        val once = rrule == null
        return ReminderSpec(
            title = title, note = note, day = day, rrule = rrule,
            remindBefore = if (once) remindOn.mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
                .map { ChronoUnit.DAYS.between(it, day).toInt() } else emptyList(),
            ring = Ring.NOTICE,
            nag = if (once && carryOver) Nag.DAILY else Nag.NONE
        )
    }
    val local = at.atZoneSameInstant(zone)
    return ReminderSpec(
        title = title, note = note, day = local.toLocalDate(), times = listOf(local.toLocalTime().withSecond(0).withNano(0)),
        rrule = rrule, ring = if (gentle) Ring.NOTICE else Ring.ALARM, wallClockAnchored = wallClockAnchored
    )
}
