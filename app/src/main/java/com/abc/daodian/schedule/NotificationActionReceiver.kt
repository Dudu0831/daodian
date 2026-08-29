package com.abc.daodian.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.notify.Notifier
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
                val db = DaodianDatabase.get(app)
                val now = System.currentTimeMillis()
                when (action) {
                    ACTION_DONE -> {
                        db.reminderDao().setStatus(id, ReminderStatus.DONE, now)
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
