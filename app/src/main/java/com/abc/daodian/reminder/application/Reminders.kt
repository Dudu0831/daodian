package com.abc.daodian.reminder.application

import android.content.Context
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.scheduling.DayTasks
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.widget.WidgetUpdater
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 提醒的所有写操作，app 里只有这一个入口：模型建的（`create_reminder`）、手动建 / 改、完成、删、撤销、重排。
 * 响铃、通知按钮、小组件按钮、巡检这些 app 外面的路径在 scheduling / delivery / widget 里，各自已经排好。
 *
 * 只调 Rescheduler 的公开入口，不碰 scheduling / data 的内部。改完一律喊小组件重画一次（[WidgetUpdater]）——
 * 它在别的进程里，收不到 Room 的 Flow。
 */
object Reminders {

    /**
     * 模型建出来的一条提醒「落库 → 排闹钟」，返回新提醒的 id。对话页和桌面速记都经由 `create_reminder` 到这里。
     * 桌面上那一行先亮成朱砂的「刚记下」（[WidgetUpdater.announce]），速记记完回到桌面就看得见。
     *
     * 整段 NonCancellable：插完库、闹钟还没排上的那一瞬间如果被取消（页面关了、桌面速记被 Home 掉），
     * 就会留下一条 SCHEDULED 却没有闹钟的记录，要等下一个重排触发源才补得上。
     */
    suspend fun commitPlan(context: Context, rawInput: String, plan: ReminderPlan, parsedBy: String): Long =
        withContext(NonCancellable) {
            val app = context.applicationContext
            val dao = ReminderDatabase.get(app).reminderDao()
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
            WidgetUpdater.announce(app, id)
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

    /**
     * 手动建 / 改一条提醒 —— 逃生舱，必须能完全脱离 AI 用。见 DESIGN.md §05
     *
     * [dueDay] 不为 null 就是当天事项：[triggerAt] 不看，换成那天的收尾时刻，一律墙钟锚定。
     */
    suspend fun upsertManual(
        context: Context,
        id: Long?,
        title: String,
        note: String?,
        triggerAt: Long,
        rrule: String?,
        wallClockAnchored: Boolean,
        dueDay: LocalDate? = null
    ) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        if (dueDay != null) {
            val check = DayTasks.checkTime(app)
            upsert(
                app, id, title, note, rrule,
                triggerAt = DayTasks.triggerFor(dueDay, check, zone, now),
                localTime = check.toString(), wallClockAnchored = true, dueDay = dueDay.toString(), now = now
            )
        } else {
            val localTime = if (wallClockAnchored) {
                Instant.ofEpochMilli(triggerAt).atZone(zone).toLocalTime()
                    .withSecond(0).withNano(0).toString()
            } else null
            upsert(app, id, title, note, rrule, triggerAt, localTime, wallClockAnchored, dueDay = null, now = now)
        }
        WidgetUpdater.refresh(app)
    }

    private suspend fun upsert(
        context: Context,
        id: Long?,
        title: String,
        note: String?,
        rrule: String?,
        triggerAt: Long,
        localTime: String?,
        wallClockAnchored: Boolean,
        dueDay: String?,
        now: Long
    ) {
        val dao = ReminderDatabase.get(context).reminderDao()
        val rescheduler = Rescheduler(context)
        val zone = ZoneId.systemDefault()

        if (id == null) {
            val reminder = Reminder(
                title = title, note = note, rawInput = title,
                nextTriggerAt = triggerAt, rrule = rrule, zoneId = zone.id,
                localTime = localTime, wallClockAnchored = wallClockAnchored, dueDay = dueDay,
                createdAt = now, updatedAt = now
            )
            val newId = dao.insert(reminder)
            dao.byId(newId)?.let { rescheduler.schedule(it) }
        } else {
            val existing = dao.byId(id) ?: return
            rescheduler.cancel(id)
            val updated = existing.copy(
                title = title, note = note, nextTriggerAt = triggerAt, rrule = rrule,
                localTime = localTime, wallClockAnchored = wallClockAnchored, dueDay = dueDay,
                status = ReminderStatus.SCHEDULED, updatedAt = now
            )
            dao.update(updated)
            rescheduler.schedule(updated)
        }
    }

    suspend fun markDone(context: Context, r: Reminder) {
        val app = context.applicationContext
        // 当天事项有自己的「完成」：重复的只算今天这一次。见 DayTasks.complete
        if (!DayTasks.complete(app, r)) {
            Rescheduler(app).cancel(r.id)
            Notifier.cancel(app, r.id)
            ReminderDatabase.get(app).reminderDao().setStatus(r.id, ReminderStatus.DONE, System.currentTimeMillis())
        }
        WidgetUpdater.refresh(app)
    }

    suspend fun delete(context: Context, r: Reminder) {
        val app = context.applicationContext
        Rescheduler(app).cancel(r.id)
        Notifier.cancel(app, r.id)
        ReminderDatabase.get(app).reminderDao().delete(r)
        WidgetUpdater.refresh(app)
    }

    /**
     * 列表页的「撤销」：把完成 / 删除之前那一整条原样放回去。
     * 删除是当场真删的（不是等撤销过期再删）—— 进程在这几秒里被杀，结果也只是「删了」，
     * 不会留下一条库里有、闹钟没有的记录。放回来之后照常排闹钟；已经过点的交给巡检补发。
     */
    suspend fun restore(context: Context, r: Reminder) {
        val app = context.applicationContext
        val dao = ReminderDatabase.get(app).reminderDao()
        if (dao.byId(r.id) != null) dao.update(r) else dao.insert(r)
        if (r.status == ReminderStatus.SCHEDULED && r.nextTriggerAt > System.currentTimeMillis()) {
            Rescheduler(app).schedule(r)
        }
        WidgetUpdater.refresh(app)
    }

    suspend fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        Rescheduler(app).rescheduleAll()
        WidgetUpdater.refresh(app)
    }

    /** 当天事项晚上几点提醒。改完全部当天事项的闹钟跟着挪（DayTasks 里） */
    suspend fun setDayCheckTime(context: Context, time: LocalTime) {
        val app = context.applicationContext
        DayTasks.setCheckTime(app, time)
        WidgetUpdater.refresh(app)
    }

    suspend fun clearLogs(context: Context) = ReminderDatabase.get(context).fireLogDao().clear()

    /**
     * M1 的出口条件：20 条覆盖未来 48 小时、必然包含凌晨时段的提醒。
     * 排完就把手机揣兜里别碰，48 小时后回来看日志。见设计文档 §9.3（眼下没有入口，要跑时临时接一个按钮）
     */
    suspend fun startSoakTest(context: Context) {
        val app = context.applicationContext
        val dao = ReminderDatabase.get(app).reminderDao()
        val rescheduler = Rescheduler(app)
        val now = System.currentTimeMillis()
        val step = TimeUnit.HOURS.toMillis(48) / 20
        repeat(20) { i ->
            val r = Reminder(
                title = "放置测试 #${i + 1}",
                rawInput = "soak",
                nextTriggerAt = now + step * (i + 1),
                zoneId = ZoneId.systemDefault().id,
                createdAt = now,
                updatedAt = now
            )
            val id = dao.insert(r)
            dao.byId(id)?.let { rescheduler.schedule(it) }
        }
        WidgetUpdater.refresh(app)
    }
}
