package com.abc.daodian.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.notify.Notifier
import com.abc.daodian.schedule.Rescheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 小组件上那个 ✓。
 *
 * 语义和列表页的「完成」完全一致（[com.abc.daodian.ui.MainViewModel.markDone]）：
 * **先撤闹钟再改状态**，重复提醒也一并停掉整条。顺序不能反 ——
 * 反了就会留下一个指向 DONE 记录的闹钟，到点照响。
 *
 * 没有复用 NotificationActionReceiver.ACTION_DONE：那条路径处理的是「已经响过的提醒」，
 * 不撤闹钟；小组件按的是**还没到点**的提醒，必须撤。
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
                Rescheduler(app).cancel(id)
                Notifier.cancel(app, id)
                DaodianDatabase.get(app).reminderDao()
                    .setStatus(id, ReminderStatus.DONE, System.currentTimeMillis())
                Log.i(TAG, "小组件标记完成 id=$id")
                WidgetUpdater.refresh(app)
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
