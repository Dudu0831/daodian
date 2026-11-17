package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.remindBeforeColumn
import com.abc.daodian.reminder.data.timesColumn
import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.runtime.tool.ToolContext
import java.time.LocalTime
import java.time.ZoneId

/** 内存里的提醒，测试用。建的交给 [commit]（不给就自己插一条）；下一下的时刻粗算成那天头一个钟点（不定的 20:00） */
class FakeReminderDesk(
    seed: List<Reminder> = emptyList(),
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai"),
    private val commit: (suspend (ReminderSpec, ToolContext) -> Long)? = null
) : ReminderDesk {

    val reminders = seed.toMutableList()
    val updates = mutableListOf<Pair<Long, ReminderSpec>>()

    override suspend fun all(): List<Reminder> = reminders.toList()

    override suspend fun create(spec: ReminderSpec, context: ToolContext): Long {
        commit?.let { return it(spec, context) }
        val id = (reminders.maxOfOrNull { it.id } ?: 0L) + 1
        reminders += Reminder(id = id, title = spec.title, nextTriggerAt = 0, zoneId = zone.id, day = spec.day, createdAt = 0, updatedAt = 0).with(spec)
        return id
    }

    override suspend fun update(id: Long, spec: ReminderSpec, context: ToolContext): Reminder? {
        val i = reminders.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        updates += id to spec
        reminders[i] = reminders[i].with(spec)
        return reminders[i]
    }

    override suspend fun stop(ids: List<Long>): List<Reminder> =
        reminders.filter { it.id in ids && it.status == ReminderStatus.SCHEDULED }.onEach { r ->
            reminders[reminders.indexOf(r)] = r.copy(status = ReminderStatus.CANCELLED)
        }

    override suspend fun delete(ids: List<Long>): List<Reminder> {
        val gone = reminders.filter { it.id in ids }
        reminders.removeAll(gone)
        return gone
    }

    private fun Reminder.with(spec: ReminderSpec) = copy(
        title = spec.title, note = spec.note, rrule = spec.rrule, day = spec.day, times = timesColumn(spec.times),
        slot = spec.times.firstOrNull()?.toString(), remindBefore = remindBeforeColumn(spec.remindBefore),
        ring = spec.ring, nag = spec.nag, wallClockAnchored = spec.wallClockAnchored, status = ReminderStatus.SCHEDULED,
        nextTriggerAt = spec.day.atTime(spec.times.firstOrNull() ?: LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
    )
}
