package com.abc.daodian.reminder.delivery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.data.ReminderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知上、全屏页上的「完成」和「稍后 10 分钟」。按钮上记着它说的是哪一下（[Fired]），
 * 交给 [Reminders.complete] / [Reminders.snooze] —— 和提醒页的圈、小组件的 ✓ 走同一个入口（§4.6）
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (id < 0) return
        val action = intent.action ?: return
        val fired = Fired.from(intent)

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_DONE -> ReminderDatabase.get(app).reminderDao().byId(id)?.let { Reminders.complete(app, it, fired) }
                        ?: Notifier.cancel(app, id)
                    ACTION_SNOOZE -> Reminders.snooze(app, id, fired)
                }
                Log.i(TAG, "$action id=$id ${fired ?: ""}")
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
    }
}
