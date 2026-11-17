package com.abc.daodian.reminder.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.shared.navigation.Launch
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.Reminder
import java.time.LocalTime
import java.time.ZoneId

/**
 * AlarmManager 里的排期是**易失**的 —— 重启、应用更新、被 ROM 强杀都会让它和 Room 脱节。
 * Room 是唯一真相，这个类负责把排期重新推回一致。见设计文档 §5.3
 */
class Rescheduler(private val context: Context) {

    private val am: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val db by lazy { ReminderDatabase.get(context) }

    fun canScheduleExact(): Boolean = am.canScheduleExactAlarms()

    /** 排一条。用 setAlarmClock —— 系统最高优先级那一档，见 DESIGN.md §5.1 */
    fun schedule(reminder: Reminder) {
        val op = alarmIntent(reminder) ?: return
        val show = PendingIntent.getActivity(
            context, 0,
            Launch.intent(context),
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
     * 排到 [beat]：先撤旧闹钟，库里写成这一下，再照库里的排。响完、点完成、改收尾时刻、换时区都走这里 ——
     * 顺序不能反：反了会留下一个指向旧时刻的闹钟
     */
    suspend fun arm(reminder: Reminder, beat: Beat) {
        cancel(reminder.id)
        val dao = db.reminderDao()
        dao.arm(reminder.id, beat.day, beat.slot?.toString(), beat.phase, beat.nagCount, beat.at, System.currentTimeMillis())
        dao.byId(reminder.id)?.let { schedule(it) }
    }

    /**
     * 时区变了、收尾时刻改了：排着的每一下按新的重新落（[Schedule.realign]）。
     * 跟着人走的「每天早上 8 点」到了新时区还得是当地 8 点；固定那一瞬间的不动。见设计文档 §7.1、§4.6
     */
    suspend fun realignAll(check: LocalTime): Int {
        val here = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        var changed = 0
        db.reminderDao().allScheduled().forEach { r ->
            val beat = Schedule.realign(r, now, check, Schedule.zoneOf(r, here)) ?: return@forEach
            if (beat.at != r.nextTriggerAt || beat.day != r.day || beat.phase != r.phase) {
                arm(r, beat)
                changed++
            }
        }
        Log.i(TAG, "重新落：$changed 条挪了，时区=$here，收尾时刻=$check")
        // 没挪的时间戳没变，但闹钟可能已经掉了，一并重排
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
