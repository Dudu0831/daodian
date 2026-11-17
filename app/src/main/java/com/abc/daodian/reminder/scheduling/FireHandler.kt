package com.abc.daodian.reminder.scheduling

import android.content.Context
import android.util.Log
import com.abc.daodian.reminder.data.FireLog
import com.abc.daodian.reminder.data.FireSource
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.widget.WidgetUpdater

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
        val db = ReminderDatabase.get(context)

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
        Log.i(TAG, "响了: id=${reminder.id} 「${reminder.title}」 ${reminder.phase} source=$source 漂移=${drift}ms")

        // 2. 发通知。送不到要大声记一笔 —— 「日志说响了但用户没看见」是最坏的一种失败，
        //    它会让投递日志变成假证据。
        val delivered = Notifier.fire(context, reminder, lateBy = drift)
        if (!delivered) {
            Log.e(TAG, "id=${reminder.id}「${reminder.title}」已触发但通知未送达用户 —— 去「体检」页查通知权限")
        }

        // 3. 排下一下（§4.6 那张表）：提前的翻到下一下、正点后要催的排催、重复的排下一次
        val next = Schedule.afterFire(reminder, firedAt, CheckTime.get(context), Schedule.zoneOf(reminder))
        if (next != null) {
            Rescheduler(context).arm(reminder, next)
            Log.i(TAG, "已排下一下: id=${reminder.id} ${next.phase} ${next.day} at=${next.at}")
        } else {
            // 一次性的走完了：送到了就落成过去的一行，不等你点（用户 11-16 定的）。
            // 没送到的（通知权限、渠道被关）留在 FIRED —— 列表上那行「响过了，还没点完成」至少还看得见
            db.reminderDao().setStatus(reminder.id, if (delivered) ReminderStatus.DONE else ReminderStatus.FIRED, firedAt)
        }
        // 4. 桌面上那条得跟着消失/翻到下一次。漏了只是显示旧数据，不影响触发
        WidgetUpdater.refresh(context)
    }
}
