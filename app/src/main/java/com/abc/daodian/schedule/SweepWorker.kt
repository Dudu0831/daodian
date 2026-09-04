package com.abc.daodian.schedule

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.FireSource
import com.abc.daodian.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * 兜底巡检。**第二道网，不是保险** ——
 * WorkManager 自己也会被 MagicOS 掐掉，主路径必须自己站得住。见设计文档 §5.4
 *
 * 干两件事：
 *  1. 补发：已过期但还没响的，立刻发（标注补发）
 *  2. 补排：未来 24h 内的，探测闹钟是否还在，掉了就补上
 */
class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val now = System.currentTimeMillis()
        val db = DaodianDatabase.get(app)
        val rescheduler = Rescheduler(app)

        return try {
            // 1. 补发漏网的
            val overdue = db.reminderDao().overdue(now)
            overdue.forEach { r ->
                Log.w(TAG, "发现漏网提醒 id=${r.id}「${r.title}」，迟了 ${(now - r.nextTriggerAt) / 1000}s —— 主闹钟路径可能正在被掐")
                FireHandler.fire(app, r, FireSource.SWEEP, now)
            }

            // 2. 补排掉了的闹钟
            val horizon = now + TimeUnit.HOURS.toMillis(24)
            var repaired = 0
            db.reminderDao().allScheduled()
                .filter { it.nextTriggerAt in (now + 1)..horizon }
                .forEach { r ->
                    if (!rescheduler.isScheduled(r.id)) {
                        Log.w(TAG, "闹钟掉了，补排 id=${r.id}「${r.title}」")
                        rescheduler.schedule(r)
                        repaired++
                    }
                }

            Log.i(TAG, "巡检完成：补发 ${overdue.size} 条，补排 $repaired 条")
            WidgetUpdater.refresh(app)
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "巡检失败", t)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "Daodian/Sweep"
        private const val WORK_NAME = "daodian_sweep"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<SweepWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
