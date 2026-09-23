package com.abc.daodian.ledger.organize

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.abc.daodian.ledger.LedgerSettings
import java.util.concurrent.TimeUnit

/**
 * 定期整理：每 N 小时（设置里改，默认 3）醒一次，有待整理的才叫模型。
 *
 * 荣耀会拖延后台任务，「每 3 小时」实际可能 5、6 小时 —— 不影响正确性，
 * 每晚对账前那次强制整理兜底（§8.8）。要联网：没网叫了模型也是白叫。
 */
class OrganizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "periodic"
        // 失败也不让 WorkManager 重试：网关挂了就等下个周期，别连环重试（§8.7）
        runCatching { Organizer.run(applicationContext, reason, respectCooldown = reason == "periodic") }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "ledger-organize"
        private const val NOW = "ledger-organize-now"
        private const val KEY_REASON = "reason"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** 按设置里的间隔排上（改了间隔也调它，UPDATE 会换成新的周期） */
        suspend fun schedule(context: Context) {
            val hours = LedgerSettings.organizeHours(context).toLong()
            val request = PeriodicWorkRequestBuilder<OrganizeWorker>(hours, TimeUnit.HOURS)
                .setConstraints(online)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** 现在就整理一次（设置页「现在整理」、对账前）。不看冷却 */
        fun runNow(context: Context, reason: String = "manual") {
            val request = OneTimeWorkRequestBuilder<OrganizeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_REASON to reason))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
