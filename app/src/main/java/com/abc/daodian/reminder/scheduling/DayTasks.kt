package com.abc.daodian.reminder.scheduling

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.dueDate
import com.abc.daodian.reminder.data.isAllDay
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.domain.Rrule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dayTaskStore by preferencesDataStore("day_tasks")

/**
 * 当天事项：只说了哪天、没说几点。见 DESIGN.md §4.3
 *
 * 对调度层来说它就是一条「收尾时刻」响的普通提醒 —— 排期、重排、巡检、投递日志一行都没为它改。
 * 不一样的只有三处，全在这个文件里说清楚：
 *  1. 触发时刻 = 那一天的收尾时刻（[triggerFor]）
 *  2. 响了没做完 → 顺延到下一天的收尾时刻，不收尾（[carryOver]，FireHandler 调）
 *  3. 重复的当天事项点「完成」= 这一次做完了，翻到下一次，不是停掉整条（[complete]）
 */
object DayTasks {

    val DEFAULT_CHECK: LocalTime = LocalTime.of(20, 0)

    private val CHECK_TIME = stringPreferencesKey("check_time")

    fun checkTimeFlow(context: Context): Flow<LocalTime> =
        context.applicationContext.dayTaskStore.data.map { p ->
            p[CHECK_TIME]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: DEFAULT_CHECK
        }

    suspend fun checkTime(context: Context): LocalTime = checkTimeFlow(context).first()

    /**
     * [dueDay] 那天的收尾时刻；已经过了（晚上十点才说「今天把报销交了」）就是下一天的 ——
     * 事情还算今天的（dueDay 不变），只是今晚不再专门吵一次。
     */
    fun triggerFor(dueDay: LocalDate, check: LocalTime, zone: ZoneId, now: Long): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var day = maxOf(dueDay, today)
        while (true) {
            val at = ZonedDateTime.of(day, check, zone).toInstant().toEpochMilli()
            if (at > now) return at
            day = day.plusDays(1)
        }
    }

    /**
     * 一次性的当天事项响完了还没做：挪到后面第一个还没过的收尾时刻，保持 SCHEDULED。
     * 钟点沿用这一次的（它就是收尾时刻），所以这里不用去读设置。补发时一路推到未来为止。
     */
    suspend fun carryOver(context: Context, reminder: Reminder, firedAt: Long) {
        val zone = runCatching { ZoneId.of(reminder.zoneId) }.getOrDefault(ZoneId.systemDefault())
        var next = ZonedDateTime.ofInstant(Instant.ofEpochMilli(reminder.nextTriggerAt), zone).plusDays(1)
        while (next.toInstant().toEpochMilli() <= firedAt) next = next.plusDays(1)

        val at = next.toInstant().toEpochMilli()
        val dao = ReminderDatabase.get(context).reminderDao()
        dao.setNextTrigger(reminder.id, at, firedAt)
        dao.byId(reminder.id)?.let { Rescheduler(context).schedule(it) }
        Log.i(TAG, "当天事项没做完，顺延: id=${reminder.id}「${reminder.title}」→ $next（原定 ${reminder.dueDay}）")
    }

    /** 重复的当天事项响完了：翻到下一次，dueDay 跟着走 —— 这一次没做就算了，不往后拖 */
    suspend fun advanceRepeating(context: Context, reminder: Reminder, next: ZonedDateTime, now: Long) {
        val dao = ReminderDatabase.get(context).reminderDao()
        dao.update(
            reminder.copy(
                nextTriggerAt = next.toInstant().toEpochMilli(),
                dueDay = next.toLocalDate().toString(),
                status = ReminderStatus.SCHEDULED,
                updatedAt = now
            )
        )
        dao.byId(reminder.id)?.let { Rescheduler(context).schedule(it) }
    }

    /**
     * 「完成」。列表、小组件、通知按钮三处都走这里，只管当天事项；返回 false 表示不是当天事项、调用方照旧处理。
     *
     * 重复的：还停在今天（或更早）这一次 → 翻到下一次；已经翻过去了（通知是响完才点的）→ 只收通知。
     * 一次性的：先撤闹钟再改状态，顺序同 WidgetActionReceiver 的说明。
     */
    suspend fun complete(context: Context, reminder: Reminder): Boolean {
        if (!reminder.isAllDay) return false
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val zone = runCatching { ZoneId.of(reminder.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        Notifier.cancel(app, reminder.id)

        if (reminder.rrule != null && reminder.status == ReminderStatus.SCHEDULED) {
            val due = reminder.dueDate() ?: today
            if (due.isAfter(today)) {
                Log.i(TAG, "重复当天事项已经翻到 $due，只收通知 id=${reminder.id}")
                return true
            }
            var next = Rrule.nextAfter(
                reminder.rrule, ZonedDateTime.ofInstant(Instant.ofEpochMilli(reminder.nextTriggerAt), zone), zone
            )
            // 至少翻过今天：今天这次做完了，明天之前不该再出现
            while (next != null && !next.toLocalDate().isAfter(today)) next = Rrule.nextAfter(reminder.rrule, next, zone)
            if (next != null) {
                Rescheduler(app).cancel(reminder.id)
                advanceRepeating(app, reminder, next, now)
                Log.i(TAG, "重复当天事项今天做完了，下一次 $next id=${reminder.id}")
                return true
            }
            // 序列到头了（UNTIL / COUNT）—— 当成最后一次，照一次性的收尾
        }

        Rescheduler(app).cancel(reminder.id)
        ReminderDatabase.get(app).reminderDao().setStatus(reminder.id, ReminderStatus.DONE, now)
        Log.i(TAG, "当天事项完成 id=${reminder.id}「${reminder.title}」")
        return true
    }

    /** 改了收尾时刻：存下来，所有还挂着的当天事项换到新钟点重排 */
    suspend fun setCheckTime(context: Context, time: LocalTime) {
        val app = context.applicationContext
        app.dayTaskStore.edit { it[CHECK_TIME] = time.toString() }

        val dao = ReminderDatabase.get(app).reminderDao()
        val rescheduler = Rescheduler(app)
        val now = System.currentTimeMillis()
        var changed = 0
        dao.allScheduled().filter { it.isAllDay }.forEach { r ->
            val zone = runCatching { ZoneId.of(r.zoneId) }.getOrDefault(ZoneId.systemDefault())
            val moved = if (r.rrule == null) {
                // 一次性的：从它算哪天的（拖过来的就是今天）起，第一个还没过的新钟点。
                // 所以今晚已经提醒过、又把钟点往后改，今晚会按新钟点再提醒一次 —— 改设置的人要的就是这个
                ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(triggerFor(r.dueDate() ?: LocalDate.now(zone), time, zone, now)), zone
                )
            } else {
                // 重复的：这一次挂在哪天就在哪天换钟点；已经过了就翻到下一次（每周三的不能挪到周四）
                val day = Instant.ofEpochMilli(r.nextTriggerAt).atZone(zone).toLocalDate()
                var at = ZonedDateTime.of(day, time, zone)
                while (at.toInstant().toEpochMilli() <= now) at = Rrule.nextAfter(r.rrule, at, zone) ?: at.plusDays(1)
                at
            }
            rescheduler.cancel(r.id)
            dao.update(
                r.copy(
                    nextTriggerAt = moved.toInstant().toEpochMilli(),
                    dueDay = if (r.rrule != null) moved.toLocalDate().toString() else r.dueDay,
                    localTime = time.toString(),
                    updatedAt = now
                )
            )
            dao.byId(r.id)?.let { rescheduler.schedule(it) }
            changed++
        }
        Log.i(TAG, "收尾时刻改成 $time，重排了 $changed 条当天事项")
    }

    private const val TAG = "Daodian/DayTask"
}
