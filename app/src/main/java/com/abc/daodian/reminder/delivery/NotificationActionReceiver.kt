package com.abc.daodian.reminder.delivery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.scheduling.DayTasks
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.widget.WidgetUpdater
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
                        // 当天事项有自己的「完成」：重复的只算这一次。见 DayTasks.complete
                        val r = db.reminderDao().byId(id)
                        if (r == null || !DayTasks.complete(app, r)) {
                            db.reminderDao().setStatus(id, ReminderStatus.DONE, now)
                        }
                        Log.i(TAG, "标记完成 id=$id")
                    }
                    ACTION_SNOOZE -> {
                        val at = now + SNOOZE_MILLIS
                        db.reminderDao().setStatus(id, ReminderStatus.SCHEDULED, now)
                        db.reminderDao().setNextTrigger(id, at, now)
                        db.reminderDao().byId(id)?.let { Rescheduler(app).schedule(it) }
                        Log.i(TAG, "稍后 10 分钟 id=$id")
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

    companion object {
        const val TAG = "Daodian/NotifAction"
        const val ACTION_DONE = "com.abc.daodian.action.DONE"
        const val ACTION_SNOOZE = "com.abc.daodian.action.SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val SNOOZE_MILLIS = 10 * 60 * 1000L
    }
}
