package com.abc.daodian.schedule

import android.content.Context
import android.util.Log
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.FireLog
import com.abc.daodian.data.FireSource
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.notify.Notifier
import com.abc.daodian.recur.Rrule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「一条提醒响了」之后要做的全部事情。
 * AlarmReceiver 和 SweepWorker 共用这一段，保证补发和正常触发走完全相同的逻辑。
 */
object FireHandler {

    private const val TAG = "Daodian/Fire"

    suspend fun fire(
        context: Context,
        reminder: Reminder,
        source: FireSource,
        firedAt: Long = System.currentTimeMillis()
    ) {
        val db = DaodianDatabase.get(context)

        // 1. 先写日志。即使后面崩了也留下证据 —— 这是 M1 验收的唯一依据
        db.fireLogDao().insert(
            FireLog(
                reminderId = reminder.id,
                title = reminder.title,
                scheduledAt = reminder.nextTriggerAt,
                firedAt = firedAt,
                source = source
            )
        )
        val drift = firedAt - reminder.nextTriggerAt
        Log.i(TAG, "响了: id=${reminder.id} 「${reminder.title}」 source=$source 漂移=${drift}ms")

        // 2. 发通知。送不到要大声记一笔 —— 「日志说响了但用户没看见」是最坏的一种失败，
        //    它会让投递日志变成假证据。
        val delivered = Notifier.fire(context, reminder, lateBy = drift)
        if (!delivered) {
            Log.e(TAG, "id=${reminder.id}「${reminder.title}」已触发但通知未送达用户 —— 去「体检」页查通知权限")
        }

        // 3. 重复的算下次，一次性的收尾
        val zone = runCatching { ZoneId.of(reminder.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(reminder.nextTriggerAt), zone)
        val next = Rrule.nextAfter(reminder.rrule, current, zone)

        if (next != null) {
            // 补发场景下算出来的「下次」可能仍在过去，一路推到未来为止
            var candidate = next
            var guard = 0
            while (candidate != null && candidate.toInstant().toEpochMilli() <= firedAt && guard < 1000) {
                candidate = Rrule.nextAfter(reminder.rrule, candidate, zone)
                guard++
            }
            if (candidate != null) {
                val at = candidate.toInstant().toEpochMilli()
                db.reminderDao().setNextTrigger(reminder.id, at, firedAt)
                db.reminderDao().byId(reminder.id)?.let { Rescheduler(context).schedule(it) }
                Log.i(TAG, "已排下一次: id=${reminder.id} at=$candidate")
                return
            }
        }

        db.reminderDao().setStatus(reminder.id, ReminderStatus.FIRED, firedAt)
    }
}
