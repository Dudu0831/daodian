package com.abc.daodian.reminder.widget

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
 * 小组件上那个 ✓。
 *
 * 和列表页的圈、通知上的「完成」是同一个入口（[Reminders.complete]）：只算这一次，
 * 一次性的办完，重复的排下一次（§4.6）。
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (id < 0) return

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                ReminderDatabase.get(app).reminderDao().byId(id)?.let { Reminders.complete(app, it) }
                Log.i(TAG, "小组件标记完成 id=$id")
            } catch (t: Throwable) {
                Log.e(TAG, "小组件完成失败 id=$id", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "Daodian/Widget"
        const val ACTION_DONE = "com.abc.daodian.widget.action.DONE"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}
