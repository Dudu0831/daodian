package com.abc.daodian.ui

import android.content.Context
import com.abc.daodian.ai.PlanValidator
import com.abc.daodian.ai.ReminderPlan
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.Reminder
import com.abc.daodian.schedule.DayTasks
import com.abc.daodian.schedule.Rescheduler
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 模型建出来的一条提醒「落库 → 排闹钟」。对话页（MainViewModel）和桌面速记（ui/quick）都走这里 ——
 * ParseEvent 那边写死过：没有第二条落库路径。
 *
 * 只调 Rescheduler 的公开入口，不碰 schedule/ 和 data/ 的内部。
 */
object PlanCommitter {

    /**
     * 返回新提醒的 id。
     *
     * 整段 NonCancellable：插完库、闹钟还没排上的那一瞬间如果被取消（页面关了、桌面速记被 Home 掉），
     * 就会留下一条 SCHEDULED 却没有闹钟的记录，要等下一个重排触发源才补得上。
     */
    suspend fun commit(context: Context, rawInput: String, plan: ReminderPlan, parsedBy: String): Long =
        withContext(NonCancellable) {
            val app = context.applicationContext
            val dao = DaodianDatabase.get(app).reminderDao()
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val reminder = if (plan.allDay) dayTask(app, rawInput, plan, parsedBy, zone, now) else Reminder(
                title = plan.title,
                note = plan.note,
                rawInput = rawInput,
                nextTriggerAt = PlanValidator.triggerMillis(plan),
                rrule = plan.rrule,
                zoneId = zone.id,
                localTime = if (plan.wallClockAnchored) PlanValidator.localTimeOf(plan, zone) else null,
                wallClockAnchored = plan.wallClockAnchored,
                parsedBy = parsedBy,
                createdAt = now,
                updatedAt = now
            )
            val id = dao.insert(reminder)
            dao.byId(id)?.let { Rescheduler(app).schedule(it) }
            id
        }

    /**
     * 当天事项：日期取模型给的，钟点换成收尾时刻。一律墙钟锚定 —— 「晚上八点提醒」到哪个时区都该是当地八点。
     * 见 DESIGN.md §4.3
     */
    private suspend fun dayTask(
        context: Context, rawInput: String, plan: ReminderPlan, parsedBy: String, zone: ZoneId, now: Long
    ): Reminder {
        val check = DayTasks.checkTime(context)
        val due = PlanValidator.dueDayOf(plan)
        return Reminder(
            title = plan.title,
            note = plan.note,
            rawInput = rawInput,
            nextTriggerAt = DayTasks.triggerFor(due, check, zone, now),
            rrule = plan.rrule,
            zoneId = zone.id,
            localTime = check.toString(),
            wallClockAnchored = true,
            parsedBy = parsedBy,
            dueDay = due.toString(),
            createdAt = now,
            updatedAt = now
        )
    }
}
