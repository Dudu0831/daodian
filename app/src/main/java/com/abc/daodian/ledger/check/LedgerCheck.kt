package com.abc.daodian.ledger.check

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abc.daodian.harness.builtin.ledger.Direction
import com.abc.daodian.harness.builtin.ledger.ExpenseQuery
import com.abc.daodian.harness.builtin.ledger.LedgerJson
import com.abc.daodian.harness.builtin.ledger.Money
import com.abc.daodian.harness.builtin.ledger.TxnState
import com.abc.daodian.ledger.LedgerSettings
import com.abc.daodian.ledger.LedgerStore
import com.abc.daodian.ledger.organize.Organizer
import com.abc.daodian.widget.WidgetLaunch
import com.abc.daodian.widget.WidgetTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 每晚对账（LEDGER_PLAN.md §4 ③）：到点先强制整理一次，还有没认出来的才弹通知
 * [现在] [晚点] [今天算了]；一笔都没有就不打扰。一天最多主动问一次（「晚点」那次不算）。
 *
 * 闹钟是记账自己的，不碰 schedule/：每次响完排下一天；开机、覆盖安装、app 冷启动都重排一遍。
 */
object LedgerCheck {

    private const val CHANNEL = "ledger"
    private const val NOTIFICATION_ID = 7301
    private const val SNOOZE_MILLIS = 60 * 60 * 1000L

    const val ACTION_FIRE = "com.abc.daodian.ledger.CHECK_FIRE"
    const val ACTION_LATER = "com.abc.daodian.ledger.CHECK_LATER"
    const val ACTION_SKIP = "com.abc.daodian.ledger.CHECK_SKIP"

    /** 排下一次：「晚点」推到的时刻优先，否则下一个对账钟点 */
    suspend fun arm(context: Context) {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val time = LedgerSettings.checkTime(app)
        var next = now.toLocalDate().atTime(time).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val snoozed = LedgerSettings.snoozedUntil(app)
        val at = if (snoozed > now.toInstant().toEpochMilli() && snoozed < next.toInstant().toEpochMilli()) snoozed
        else next.toInstant().toEpochMilli()
        app.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, broadcast(app, ACTION_FIRE))
    }

    private fun broadcast(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, CheckReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 闹钟响了：交给 WorkManager 跑（整理要联网、可能要几十秒，广播里跑不完） */
    fun fire(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ledger-check", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CheckWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        )
    }

    /** 对账：先整理，再数，再决定问不问 */
    suspend fun check(context: Context) {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val today = LedgerJson.dayInt(LocalDate.now(zone))
        val snoozed = LedgerSettings.snoozedUntil(app)
        val isSnoozeFire = snoozed > 0 && kotlin.math.abs(System.currentTimeMillis() - snoozed) < 10 * 60 * 1000L
        if (isSnoozeFire) LedgerSettings.setSnoozedUntil(app, 0)

        // 后台整理正在跑就等它一会儿，然后自己再强制整理一次（连刚到的也整）
        for (attempt in 0 until 20) {
            if (Organizer.run(app, "check", respectCooldown = false) != Organizer.Result.Busy) break
            delay(15_000)
        }

        val store = LedgerStore.get(app)
        val pending = store.query(ExpenseQuery(state = TxnState.PENDING, limit = 200)).size
        val unreadable = store.dao.unreadableRaws().size
        if (pending + unreadable == 0) {
            dismiss(app)
            return
        }
        if (!isSnoozeFire && LedgerSettings.askedDay(app) == today) return

        val autoToday = store.query(ExpenseQuery(fromDay = today, toDay = today, state = TxnState.AUTO, limit = 200))
            .filter { it.direction == Direction.OUT }
        LedgerSettings.setAskedDay(app, today)
        notify(app, pending + unreadable, autoToday.size, autoToday.sumOf { it.amount })
    }

    private fun notify(context: Context, unknown: Int, autoCount: Int, autoSum: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "每晚对账", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "有没认出来的账时，晚上问你一次"
            }
        )
        val open = PendingIntent.getActivity(
            context, 7302, WidgetLaunch.intent(context, WidgetTarget.LedgerCheck),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (autoCount > 0) "另外自动记了 $autoCount 笔，共 ¥${Money.yuan(autoSum)}" else "点开在对话里说一声就行"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("有 $unknown 笔账没认出来")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "现在", open)
            .addAction(0, "晚点", broadcast(context, ACTION_LATER))
            .addAction(0, "今天算了", broadcast(context, ACTION_SKIP))
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** 对完了（或者不用对了）：通知收掉，「晚点」也取消 */
    suspend fun done(context: Context) {
        dismiss(context)
        LedgerSettings.setSnoozedUntil(context, 0)
        arm(context)
    }

    private fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    suspend fun later(context: Context) {
        dismiss(context)
        LedgerSettings.setSnoozedUntil(context, System.currentTimeMillis() + SNOOZE_MILLIS)
        arm(context)
    }

    fun skip(context: Context) = dismiss(context)
}

/** 对账闹钟、通知上的「晚点」「今天算了」，以及开机 / 覆盖安装后的重排 */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching {
                    when (intent.action) {
                        LedgerCheck.ACTION_FIRE -> LedgerCheck.fire(context)
                        LedgerCheck.ACTION_LATER -> LedgerCheck.later(context)
                        LedgerCheck.ACTION_SKIP -> LedgerCheck.skip(context)
                        else -> Unit     // 开机、覆盖安装：闹钟没了，下面重排
                    }
                    LedgerCheck.arm(context)
                }
            } finally {
                result.finish()
            }
        }
    }
}

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { LedgerCheck.check(applicationContext) }
        return Result.success()
    }
}
