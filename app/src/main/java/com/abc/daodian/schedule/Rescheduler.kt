package com.abc.daodian.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.MainActivity
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.Reminder
import com.abc.daodian.recur.Rrule
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * AlarmManager 里的排期是**易失**的 —— 重启、应用更新、被 ROM 强杀都会让它和 Room 脱节。
 * Room 是唯一真相，这个类负责把排期重新推回一致。见设计文档 §5.3
 */
class Rescheduler(private val context: Context) {

    private val am: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val db by lazy { DaodianDatabase.get(context) }

    fun canScheduleExact(): Boolean = am.canScheduleExactAlarms()

    /** 排一条。用 setAlarmClock —— 系统最高优先级那一档，见 决策 5.1 */
    fun schedule(reminder: Reminder) {
        val op = alarmIntent(reminder) ?: return
        val show = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (canScheduleExact()) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.nextTriggerAt, show), op)
        } else {
            // 理论上不该走到这儿 —— USE_EXACT_ALARM 是安装即授予的。
            // 但宁可降级也不要抛 SecurityException 崩掉整个重排流程。
            Log.w(TAG, "没有精确闹钟权限，降级到 setExactAndAllowWhileIdle: id=${reminder.id}")
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.nextTriggerAt, op)
        }
    }

    fun cancel(reminderId: Long) {
        existingAlarm(reminderId)?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    /** 这条提醒当前是否真的有闹钟排着。兜底巡检用它探测漏排，见 §5.4 */
    fun isScheduled(reminderId: Long): Boolean = existingAlarm(reminderId) != null

    /**
     * 从 Room 全量重建。开机、应用更新、兜底巡检都走这里。
     * 过期的不排 —— 交给 SweepWorker 补发，避免重启时瞬间弹一堆旧通知。
     */
    suspend fun rescheduleAll(): Int {
        val now = System.currentTimeMillis()
        val all = db.reminderDao().allScheduled()
        var armed = 0
        all.forEach { r ->
            if (r.nextTriggerAt > now) {
                schedule(r)
                armed++
            }
        }
        Log.i(TAG, "重排完成：${all.size} 条待触发，实排 $armed 条，${all.size - armed} 条已过期待补发")
        return armed
    }

    /**
     * 时区变了。墙钟锚定的提醒要**重算**而不是简单重排 ——
     * 「每天早上 8 点」到了新时区还得是当地 8 点。见设计文档 §7.1
     */
    suspend fun recomputeForTimeZone(): Int {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val dao = db.reminderDao()
        var changed = 0

        dao.wallClockAnchored().forEach { r ->
            val localTime = r.localTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return@forEach
            val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.nextTriggerAt), zone)
            val retargeted = current.with(localTime)
            val next = if (retargeted.toInstant().toEpochMilli() > now) {
                retargeted
            } else {
                Rrule.nextAfter(r.rrule, retargeted, zone) ?: retargeted.plusDays(1)
            }
            val newAt = next.toInstant().toEpochMilli()
            if (newAt != r.nextTriggerAt) {
                cancel(r.id)
                dao.setNextTrigger(r.id, newAt, now)
                dao.byId(r.id)?.let { schedule(it) }
                changed++
            }
        }
        Log.i(TAG, "时区变更重算：$changed 条墙钟锚定提醒时间已调整，时区=$zone")
        // 绝对时刻的提醒时间戳没变，但闹钟可能已经掉了，一并重排
        rescheduleAll()
        return changed
    }

    private fun alarmIntent(reminder: Reminder): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            Intent(context, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
                .putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, reminder.nextTriggerAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun existingAlarm(reminderId: Long): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

    companion object { const val TAG = "Daodian/Resched" }
}
