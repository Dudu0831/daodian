package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.clockTimes
import com.abc.daodian.reminder.data.remindBeforeDays
import com.abc.daodian.reminder.data.Ring
import java.time.LocalDate
import java.time.LocalTime

/**
 * 一条提醒要定的那几件事（DESIGN.md §4.6）：哪天、几点（空 = 不定）、重复、提前几天、怎么响、要不要催。
 * 建、改都交这一份 —— 模型的（过了 [PlanValidator] 的）和手动的一样；「这一次排到哪了」由 `Reminders` 照它算
 */
data class ReminderSpec(
    val title: String,
    val note: String? = null,
    val day: LocalDate,
    val times: List<LocalTime> = emptyList(),
    val rrule: String? = null,
    /** 提前几天，从远到近 */
    val remindBefore: List<Int> = emptyList(),
    val ring: Ring = Ring.ALARM,
    val nag: Nag = Nag.NONE,
    /** 有钟点的才看：true = 跟着人走的当地钟点，false = 固定那一瞬间（§7.1） */
    val wallClockAnchored: Boolean = true
)

/** 库里这一条现在定的几件事（「这一次排到哪了」不算）。查提醒交给模型照抄、编辑页的初值都从这里来 */
fun Reminder.spec(): ReminderSpec = ReminderSpec(
    title = title, note = note, day = day, times = clockTimes(), rrule = rrule,
    remindBefore = remindBeforeDays(), ring = ring, nag = nag, wallClockAnchored = wallClockAnchored
)
