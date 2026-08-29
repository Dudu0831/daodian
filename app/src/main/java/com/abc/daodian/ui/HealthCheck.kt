package com.abc.daodian.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.abc.daodian.notify.Notifier

data class HealthItem(
    val label: String,
    val ok: Boolean,
    val detail: String,
    val fixIntent: Intent?
)

/**
 * 权限体检。这几项里挂掉任何一项，提醒都可能不响。见设计文档 §9.1
 * 注意：MagicOS 的「应用启动管理」没有公开 API 可以检测，只能靠 §9.2 手动设置 + 放置测试验证。
 */
object HealthCheck {

    fun run(context: Context): List<HealthItem> {
        val am = context.getSystemService(AlarmManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val pkg = context.packageName

        val channel = nm.getNotificationChannel(Notifier.CHANNEL_ID)

        return listOf(
            HealthItem(
                label = "精确闹钟",
                ok = am.canScheduleExactAlarms(),
                detail = if (am.canScheduleExactAlarms()) "已授予（USE_EXACT_ALARM 安装即给）" else "没有它闹钟会被系统随意延后",
                fixIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
            ),
            HealthItem(
                label = "通知权限",
                ok = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                detail = "关掉的话闹钟会响但你看不见",
                fixIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            ),
            HealthItem(
                // 实测发现：MagicOS 会把渠道从我们请求的 IMPORTANCE_HIGH(4) 静默降到 DEFAULT(3)，
                // 且 mUserLockedFields=0 —— 不是用户改的，是 ROM 干的。
                // 后果：有声音，但不弹横幅。所以门槛必须卡在 4，卡 3 会让降级状态显示成绿的。
                label = "通知渠道重要性",
                ok = (channel?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH,
                detail = when {
                    channel == null -> "渠道还没创建"
                    channel.importance >= NotificationManager.IMPORTANCE_HIGH ->
                        "重要性 ${channel.importance}（HIGH），会弹横幅"
                    else ->
                        "重要性 ${channel.importance}，低于请求的 4 —— ROM 静默降级了。" +
                            "后果是有声音但不弹横幅，目前靠 full-screen intent 兜着。" +
                            "去设置里手动调成「紧急」"
                },
                fixIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CHANNEL_ID)
            ),
            HealthItem(
                label = "电池优化白名单",
                ok = pm.isIgnoringBatteryOptimizations(pkg),
                detail = "荣耀上这项没开，深度休眠时大概率漏提醒",
                fixIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
            ),
            HealthItem(
                label = "全屏 intent",
                ok = nm.canUseFullScreenIntent(),
                detail = "没有就降级成普通横幅，不影响响铃",
                fixIntent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
            )
        )
    }
}
