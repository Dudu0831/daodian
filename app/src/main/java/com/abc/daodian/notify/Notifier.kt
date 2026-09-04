package com.abc.daodian.notify

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
import com.abc.daodian.data.Reminder
import com.abc.daodian.schedule.NotificationActionReceiver
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.alarm.AlarmActivity

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

        return try {
            NotificationManagerCompat.from(context).notify(reminder.id.toInt(), builder.build())
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "发通知被系统拒绝，id=${reminder.id}", e)
            false
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        NotificationManagerCompat.from(context).cancel(reminderId.toInt())
    }

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
