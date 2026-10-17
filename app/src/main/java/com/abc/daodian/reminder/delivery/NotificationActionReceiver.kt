package com.abc.daodian.reminder.delivery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.scheduling.DayTasks
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.scheduling.Series
import com.abc.daodian.reminder.widget.WidgetUpdater
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 通知上的「完成」和「稍后 10 分钟」 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (id < 0) return
        val action = intent.action ?: return

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val db = ReminderDatabase.get(app)
                val now = System.currentTimeMillis()
                when (action) {
                    ACTION_DONE -> {
                        val r = db.reminderDao().byId(id)
                        when {
                            // 当天事项有自己的「完成」：重复的只算这一次。见 DayTasks.complete
                            r != null && DayTasks.complete(app, r) -> Unit
                            // 重复的定时提醒响的时候已经排到下一次了，「完成」只算刚响的这一次。见 Series
                            r != null && Series.isLive(r) -> if (r.seriesNextAt != null) resumeSeries(app, r, now)
                            else -> db.reminderDao().setStatus(id, ReminderStatus.DONE, now)
                        }
                        Log.i(TAG, "标记完成 id=$id")
                    }
                    ACTION_SNOOZE -> {
                        // 重复的只挪这一次，这一串本来的下一次记下来，稍后那次响完回到它。见 Series
                        val r = db.reminderDao().byId(id)
                        val seriesNext = r?.let { Series.seriesNextOnSnooze(it) }
                        db.reminderDao().snooze(id, now + SNOOZE_MILLIS, seriesNext, now)
                        db.reminderDao().byId(id)?.let { Rescheduler(app).schedule(it) }
                        Log.i(TAG, "稍后 10 分钟 id=$id" + if (seriesNext != null) "，之后回到 $seriesNext" else "")
                    }
                }
                Notifier.cancel(app, id)
                WidgetUpdater.refresh(app)
            } catch (t: Throwable) {
                Log.e(TAG, "通知动作失败 id=$id action=$action", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** 稍后着又点了「完成」（全屏页还开着、通知上先点了稍后）：稍后那次不用再响了，回到这一串本来的下一次 */
    private suspend fun resumeSeries(context: Context, r: Reminder, now: Long) {
        val dao = ReminderDatabase.get(context).reminderDao()
        val zone = runCatching { ZoneId.of(r.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val next = Series.nextAfterFire(r, now, zone)
        if (next != null) {
            dao.advance(r.id, next.toInstant().toEpochMilli(), now)
            dao.byId(r.id)?.let { Rescheduler(context).schedule(it) }
        } else {
            // 一串到头了：照一次性的收尾，先撤闹钟再改状态
            Rescheduler(context).cancel(r.id)
            dao.setStatus(r.id, ReminderStatus.DONE, now)
        }
    }

    companion object {
        const val TAG = "Daodian/NotifAction"
        const val ACTION_DONE = "com.abc.daodian.action.DONE"
        const val ACTION_SNOOZE = "com.abc.daodian.action.SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val SNOOZE_MILLIS = 10 * 60 * 1000L
    }
}
