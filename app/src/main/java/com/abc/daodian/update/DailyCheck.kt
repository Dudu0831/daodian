package com.abc.daodian.update

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abc.daodian.BuildConfig
import com.abc.daodian.shared.format.DiagnosticText
import com.abc.daodian.shared.navigation.Launch
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 每天 09:00、13:00、19:00 在后台另查一次更新（开 app、回设置页、点「版本」那几次照旧，见 [Updates]）。
 * 查到新版发一条通知，**同一版只发一次**；点开进设置页，「版本」那行点一下就是下载。不在后台自己下：八九十 MB，怕走流量。
 *
 * 闹钟是检查更新自己的，不碰提醒的 scheduling/：每次响完排下一个钟点；开机、覆盖安装、app 冷启动都重排一遍。
 * 响了只把活交给 WorkManager（要等有网，联网可能要十几秒，广播里跑不完）。debug 包不查，也就不排。
 */
object DailyCheck {

    /** 避开手机上常排着的 20:00 和每晚对账的 21:30，不撞在一起响 */
    val TIMES: List<LocalTime> = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(19, 0))

    private const val CHANNEL = "update"
    private const val NOTIFICATION_ID = 7401
    const val ACTION_FIRE = "com.abc.daodian.update.CHECK_FIRE"

    /** 设置首页（壳的 `ShellRoutes.SETTINGS`）。update 不 import 壳；通知栏里挂着的 PendingIntent 带着它，不改这个字 */
    private const val SETTINGS_ROUTE = "settings"

    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_NEXT = "next_at"
    private const val KEY_LAST = "last_at"
    private const val KEY_RESULT = "last_result"

    /** [now] 之后的下一个钟点；今天的都过了，就是明天第一个 */
    fun next(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate()
        return TIMES.map { today.atTime(it).atZone(now.zone) }.firstOrNull { it.isAfter(now) }
            ?: today.plusDays(1).atTime(TIMES.first()).atZone(now.zone)
    }

    fun arm(context: Context) {
        if (!Updates.enabled) return
        val app = context.applicationContext
        val at = next(ZonedDateTime.now()).toInstant().toEpochMilli()
        val am = app.getSystemService(AlarmManager::class.java)
        val fire = PendingIntent.getBroadcast(
            app, 0, Intent(app, DailyCheckReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 精确闹钟（USE_EXACT_ALARM）是提醒要的，装上就有；万一没了，晚几分钟查也不碍事
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        prefs(app).edit().putLong(KEY_NEXT, at).apply()
    }

    /** 闹钟响了：交给 WorkManager，有网了再查 */
    fun fire(context: Context) {
        if (!Updates.enabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "update-check", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DailyCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        )
    }

    /** 查一次；查到新版、这一版还没通知过，就发通知。结果记下来给导出诊断看（release 包没别的地方看得到） */
    suspend fun run(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val result = when (val s = Updates.refresh(app)) {
            null, Updates.State.Unknown -> "没查到（没网或服务器没回）"
            Updates.State.Latest -> "已是最新"
            is Updates.State.Available -> {
                val fresh = p.getInt(KEY_NOTIFIED, 0) < s.release.versionCode
                if (fresh) {
                    notify(app, s.release)
                    p.edit().putInt(KEY_NOTIFIED, s.release.versionCode).apply()
                }
                "有新版 ${s.release.versionName}（${s.release.versionCode}）· ${if (fresh) "发了通知" else "这一版通知过了"}"
            }
            is Updates.State.Downloading -> "有新版 ${s.release.versionName} · 正在下"
            is Updates.State.Ready -> "有新版 ${s.release.versionName} · 已经下好"
            is Updates.State.Failed -> "有新版 ${s.release.versionName} · 上次没下下来"
        }
        p.edit().putLong(KEY_LAST, System.currentTimeMillis()).putString(KEY_RESULT, result).apply()
    }

    private fun notify(app: Context, release: Updates.Release) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "新版本", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "后台查到新版本时提醒，同一版只提醒一次"
            }
        )
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID, Launch.intent(app, route = SETTINGS_ROUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hint = "点开在设置里下载安装，数据都在"
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("有新版本 ${release.versionName}")
            .setContentText(release.notes.ifBlank { hint })
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.takeIf { it.isNotBlank() }?.let { "$it\n$hint" } ?: hint))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** 开始下了、或者已经是最新：那条「有新版本」收掉 */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** 覆盖安装完：通知过的那一版已经装上了，那条通知还挂着就收掉（装的是更旧的一版就留着） */
    fun upgraded(context: Context) {
        if (prefs(context).getInt(KEY_NOTIFIED, 0) <= BuildConfig.VERSION_CODE) dismiss(context)
    }

    /** 导出诊断里那一行：几点查、下一次、上一次后台查的结果 */
    fun describe(context: Context): String {
        if (!Updates.enabled) return "debug 包不查"
        val p = prefs(context)
        val result = p.getString(KEY_RESULT, null)?.let { "（$it）" }.orEmpty()
        return "每天 ${TIMES.joinToString(" / ")} · 下一次 ${DiagnosticText.time(p.getLong(KEY_NEXT, 0).takeIf { it > 0 })}" +
            " · 上一次后台查 ${DiagnosticText.time(p.getLong(KEY_LAST, 0).takeIf { it > 0 })}$result"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("update_check", Context.MODE_PRIVATE)
}

/** 每天那几个钟点的闹钟，以及开机 / 覆盖安装后的重排 */
class DailyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                DailyCheck.ACTION_FIRE -> DailyCheck.fire(context)
                Intent.ACTION_MY_PACKAGE_REPLACED -> DailyCheck.upgraded(context)
                else -> Unit     // 开机：闹钟没了，下面重排
            }
            DailyCheck.arm(context)
        }
    }
}

class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { DailyCheck.run(applicationContext) }
        return Result.success()
    }
}
