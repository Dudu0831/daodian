package com.abc.daodian.agent.memory.tidy

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 闲下来的整理：对话页每说完一轮就把它往后推 [IDLE_MINUTES] 分钟（REPLACE），停下来才真跑。
 * 走 WorkManager 是因为 app 这时多半已经切走、进程可能被杀，照样要跑。要联网。
 *
 * 失败不让 WorkManager 重试：下次说完话还会再排上，别连环重试（和记账整理同一个规矩，§10.7 第 7 条）。
 */
class TidyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { Tidy.run(applicationContext, Tidy.REASON_IDLE) }
        return Result.success()
    }

    companion object {
        private const val NAME = "chat-tidy-idle"
        const val IDLE_MINUTES = 10L

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<TidyWorker>()
                .setConstraints(online)
                .setInitialDelay(IDLE_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** 关掉自动整理时：排着的那次不跑了 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
