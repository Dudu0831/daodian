package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.runtime.tool.ToolContext
import java.time.ZoneId

/** 内存里的提醒，测试用。建的交给 [commit]（不给就自己插一条）；改的只改标题、时间、重复、轻提醒 */
class FakeReminderDesk(
    seed: List<Reminder> = emptyList(),
    private val commit: (suspend (ReminderPlan, ToolContext) -> Long)? = null
) : ReminderDesk {

    val reminders = seed.toMutableList()
    val updates = mutableListOf<Pair<Long, ReminderPlan>>()

    override suspend fun all(): List<Reminder> = reminders.toList()

    override suspend fun create(plan: ReminderPlan, context: ToolContext): Long {
        commit?.let { return it(plan, context) }
        val id = (reminders.maxOfOrNull { it.id } ?: 0L) + 1
        reminders += Reminder(
            id = id, title = plan.title, nextTriggerAt = PlanValidator.triggerMillis(plan), zoneId = ZoneId.systemDefault().id,
            createdAt = 0, updatedAt = 0
        )
        return id
    }

    override suspend fun update(id: Long, plan: ReminderPlan, context: ToolContext): Reminder? {
        val i = reminders.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        updates += id to plan
        reminders[i] = reminders[i].copy(
            title = plan.title, note = plan.note, nextTriggerAt = PlanValidator.triggerMillis(plan), rrule = plan.rrule, gentle = plan.gentle
        )
        return reminders[i]
    }

    override suspend fun delete(ids: List<Long>): List<Reminder> {
        val gone = reminders.filter { it.id in ids }
        reminders.removeAll(gone)
        return gone
    }
}
