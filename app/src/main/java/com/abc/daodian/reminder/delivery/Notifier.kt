package com.abc.daodian.reminder.delivery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abc.daodian.MainActivity
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.dueDate
import com.abc.daodian.reminder.data.isAllDay
import com.abc.daodian.shared.format.Format
import com.abc.daodian.reminder.presentation.alarm.AlarmActivity
import com.abc.daodian.shared.navigation.WidgetLaunch
import com.abc.daodian.shared.navigation.WidgetTarget
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Notifier {

    /**
     * 渠道创建后铃声就改不了了 —— 要换声音必须换 id，所以带版本号。见设计文档 §5.6
     */
    const val CHANNEL_ID = "reminders_v1"
    private const val TAG = "Daodian/Notify"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点提醒。关掉它这个 app 就没用了。"
            enableVibration(true)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(channel)
    }

    /** 到点了，响。返回是否真的送达用户 —— false 表示通知没出现在屏幕上。[lateBy] > 0 表示这是补发 */
    fun fire(context: Context, reminder: Reminder, lateBy: Long): Boolean {
        if (reminder.isAllDay) return fireDayTask(context, reminder, lateBy)
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(reminder.title)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "完成", actionIntent(context, reminder.id, NotificationActionReceiver.ACTION_DONE))
            .addAction(0, "稍后 10 分钟", actionIntent(context, reminder.id, NotificationActionReceiver.ACTION_SNOOZE))

        // 视觉稿的通知形态：标题是提醒本身，副行交代「什么时候的事」。
        // 迟到必须写在脸上 —— 补发假装准时，用户下次就不敢信这个 app 了。
        val body = buildString {
            reminder.note?.let { append(it) }
            if (isEmpty()) {
                if (lateBy > 60_000) append("应在 ${Format.clock(reminder.nextTriggerAt)}")
                else append(Format.humanDateTime(reminder.nextTriggerAt))
            }
            if (lateBy > 60_000) {
                append(" · 补发，迟了 ${lateBy / 60_000} 分钟")
            }
        }
        if (body.isNotEmpty()) builder.setContentText(body)

        // Android 14+ 收紧了全屏 intent，拿不到就安静降级成 heads-up，不要崩。
        // 全屏页是独立的 AlarmActivity（响铃屏），不是打开 app 本体那个 MainActivity。
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.canUseFullScreenIntent()) {
            val ring = PendingIntent.getActivity(
                context,
                reminder.id.toInt(),
                Intent(context, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AlarmActivity.EXTRA_REMINDER_ID, reminder.id)
                    .putExtra(AlarmActivity.EXTRA_TITLE, reminder.title),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setFullScreenIntent(ring, true)
        }

        // 权限被拒时 notify() 是**静默失败**的 —— 提醒会「响」、FireLog 会记一条，
        // 但屏幕上什么都不出现。那样投递日志就成了假证据，M1 的验收依据直接作废。
        // 所以这里显式检查 + 大声报错，绝不 silent catch。
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "通知权限被拒，id=${reminder.id}「${reminder.title}」没能送达用户！去「体检」页处理")
            return false
        }

        // 不加 INSISTENT 的话，铃声和震动各播一遍就停 —— 那是普通通知，不是闹钟。
        // 加了之后一直循环到通知被取消：「完成」「稍后」（通知按钮和全屏页都走
        // NotificationActionReceiver → cancel()）、点开通知、划掉通知。
        // 不在 AlarmActivity 里自己放铃：亮屏时系统只给 heads-up、不起全屏页，那条路就又只响一下了。
        val notification = builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }

        return try {
            NotificationManagerCompat.from(context).notify(reminder.id.toInt(), notification)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "发通知被系统拒绝，id=${reminder.id}", e)
            false
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        NotificationManagerCompat.from(context).cancel(reminderId.toInt())
        // 可能是一组当天事项里的一条：组头的「还有 N 件」跟着改，最后一条没了组头也收掉
        refreshDaySummary(context, alert = false)
    }

    // ---------------- 当天事项：晚上收尾时安静地提一次 ----------------

    /**
     * 当天事项自己的渠道：普通通知的声音、不弹全屏、不循环 —— 它是「今天还有事没做」，不是闹钟。
     * 用户要是嫌吵，可以在系统设置里单独关掉这一个渠道，定时提醒不受影响。
     */
    const val DAY_CHANNEL_ID = "day_tasks_v1"
    private const val DAY_GROUP = "com.abc.daodian.DAY_TASKS"
    /** 组头的通知 id。提醒 id 是从 1 往上长的，取个负数撞不上 */
    private const val DAY_SUMMARY_ID = -20
    /** 组头这么久以前发的，就当成是上一晚的，这一次要重新出声 */
    private const val DAY_REALERT_MS = 30 * 60 * 1000L

    private fun ensureDayChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(DAY_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(DAY_CHANNEL_ID, "当天事项", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "只说了哪天、没说几点的事。晚上收尾时提一次，没做完的顺延到明天。"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /**
     * 每条一个通知（各带自己的「完成」），挂在同一组下面；只有组头出声 ——
     * 收尾时刻几条同时到点，响一下就够了，不是一条响一下。
     */
    private fun fireDayTask(context: Context, reminder: Reminder, lateBy: Long): Boolean {
        ensureDayChannel(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "通知权限被拒，当天事项 id=${reminder.id}「${reminder.title}」没能送达用户！去「体检」页处理")
            return false
        }

        val body = buildString {
            val due = reminder.dueDate()
            val today = LocalDate.now()
            val carried = due?.let { ChronoUnit.DAYS.between(it, today) } ?: 0
            append(if (carried > 0) "从 ${due!!.monthValue}月${due.dayOfMonth}日 拖过来的，第 ${carried + 1} 天" else "今天的事，还没做")
            reminder.note?.let { append(" · ").append(it) }
            if (lateBy > 60_000) append(" · 补发，迟了 ${lateBy / 60_000} 分钟")
        }
        val child = NotificationCompat.Builder(context, DAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(reminder.title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(DAY_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(openList(context))
            .addAction(0, "完成", actionIntent(context, reminder.id, NotificationActionReceiver.ACTION_DONE))
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(reminder.id.toInt(), child)
            refreshDaySummary(context, alert = true)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "发通知被系统拒绝，当天事项 id=${reminder.id}", e)
            false
        }
    }

    /**
     * 组头：「今天还有 N 件没做」+ 逐条标题。条目从通知栏里现有的子通知数，不查库 ——
     * 通知栏上挂着什么，组头就写什么，两边对不上的情况不存在。
     *
     * [alert]：这一晚第一次发要出声；同一晚后面几条跟着到点，只是静静地把数字改掉。
     */
    private fun refreshDaySummary(context: Context, alert: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val active = runCatching { nm.activeNotifications }.getOrNull() ?: return
        val children = active.filter { it.notification.group == DAY_GROUP && it.id != DAY_SUMMARY_ID }
        if (children.isEmpty()) {
            nm.cancel(DAY_SUMMARY_ID)
            return
        }
        val existing = active.firstOrNull { it.id == DAY_SUMMARY_ID }
        if (!alert && existing == null) return
        // 昨晚那个组头还挂着没划掉：先收掉，不然 onlyAlertOnce 会让今晚这一次不出声
        if (alert && existing != null && System.currentTimeMillis() - existing.postTime > DAY_REALERT_MS) {
            nm.cancel(DAY_SUMMARY_ID)
        }

        val titles = children.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
        val headline = "还有 ${children.size} 件当天的事没做"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(headline)
        titles.take(6).forEach { style.addLine(it) }
        val summary = NotificationCompat.Builder(context, DAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(headline)
            .setContentText(titles.joinToString("、"))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(DAY_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setAutoCancel(true)
            .setContentIntent(openList(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(DAY_SUMMARY_ID, summary)
        } catch (e: SecurityException) {
            Log.e(TAG, "当天事项组头发不出去", e)
        }
    }

    /** 点当天事项的通知进列表页：它们在列表最上面那一块 */
    private fun openList(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            DAY_SUMMARY_ID,
            WidgetLaunch.intent(context, WidgetTarget.List),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun actionIntent(context: Context, reminderId: Long, action: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_REMINDER_ID, reminderId)
        // action 不同 → PendingIntent 不同；request code 再加一层保险
        val requestCode = (reminderId.toInt() shl 2) or action.hashCode().and(0b11)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
