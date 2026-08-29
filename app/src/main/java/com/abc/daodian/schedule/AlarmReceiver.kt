package com.abc.daodian.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.FireSource
import com.abc.daodian.data.ReminderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 到点。
 *
 * onReceive 有约 10 秒上限，goAsync() 也只延长到几十秒 ——
 * **这里绝对不能发网络请求**。见设计文档 §5.5
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId < 0) {
            Log.w(TAG, "收到没有 reminderId 的闹钟广播，忽略")
            return
        }
        val firedAt = System.currentTimeMillis()
        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val reminder = DaodianDatabase.get(app).reminderDao().byId(reminderId)
                when {
                    reminder == null ->
                        Log.w(TAG, "闹钟指向一条已不存在的提醒 id=$reminderId")
                    reminder.status != ReminderStatus.SCHEDULED ->
                        Log.w(TAG, "提醒 id=$reminderId 状态是 ${reminder.status}，跳过")
                    else ->
                        FireHandler.fire(app, reminder, FireSource.ALARM, firedAt)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "触发失败 id=$reminderId", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "Daodian/Alarm"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
