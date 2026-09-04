package com.abc.daodian.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abc.daodian.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 重排触发源里的三个系统事件：开机、应用更新、时区/时间变更。
 * 第四个（WorkManager 周期巡检）在 SweepWorker。见设计文档 §5.3
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "收到 $action，开始重排")

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val rescheduler = Rescheduler(app)
                when (action) {
                    Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                        // 墙钟锚定的要重算，不是简单重排
                        rescheduler.recomputeForTimeZone()
                    else ->
                        rescheduler.rescheduleAll()
                }
                // 开机后 WorkManager 的周期任务不一定还在，重新登记一次
                SweepWorker.enqueue(app)
                // 开机 / 改时间之后桌面上的字大概率已经过期了
                WidgetUpdater.refresh(app)
            } catch (t: Throwable) {
                Log.e(TAG, "重排失败 action=$action", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val TAG = "Daodian/Resched" }
}
