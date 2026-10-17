package com.abc.daodian.reminder.scheduling

import android.content.Context
import android.util.Log
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.FireLog
import com.abc.daodian.reminder.data.FireSource
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.isAllDay
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.widget.WidgetUpdater
import java.time.ZoneId

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
        Log.i(TAG, "响了: id=${reminder.id} 「${reminder.title}」 source=$source 漂移=${drift}ms")

        // 2. 发通知。送不到要大声记一笔 —— 「日志说响了但用户没看见」是最坏的一种失败，
        //    它会让投递日志变成假证据。
        val delivered = Notifier.fire(context, reminder, lateBy = drift)
        if (!delivered) {
            Log.e(TAG, "id=${reminder.id}「${reminder.title}」已触发但通知未送达用户 —— 去「体检」页查通知权限")
        }

        // 3. 重复的算下次，一次性的收尾（当天事项例外：顺延）。稍后那一次响完，回到这一串本来的下一次（Series）
        val zone = runCatching { ZoneId.of(reminder.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val candidate = Series.nextAfterFire(reminder, firedAt, zone)
        if (candidate != null) {
            if (reminder.isAllDay) {
                // 当天事项的「哪一天」要跟着翻，否则列表会把明天那次当成今天没做完的
                DayTasks.advanceRepeating(context, reminder, candidate, firedAt)
            } else {
                val at = candidate.toInstant().toEpochMilli()
                db.reminderDao().advance(reminder.id, at, firedAt)
                db.reminderDao().byId(reminder.id)?.let { Rescheduler(context).schedule(it) }
            }
            Log.i(TAG, "已排下一次: id=${reminder.id} at=$candidate")
            WidgetUpdater.refresh(context)
            return
        }

        // 一次性的当天事项：响过不算完，没点「完成」就顺延到明天的收尾时刻。见 DayTasks
        if (reminder.isAllDay) {
            DayTasks.carryOver(context, reminder, firedAt)
            WidgetUpdater.refresh(context)
            return
        }

        // 轻提醒不等人确认：送到了就算完，列表里直接落成过去的一行（DESIGN.md §5.6）。
        // 没送到的（通知权限、渠道被关）留在 FIRED —— 列表上那行「响过了，还没点完成」至少还看得见
        val settled = if (reminder.gentle && delivered) ReminderStatus.DONE else ReminderStatus.FIRED
        db.reminderDao().setStatus(reminder.id, settled, firedAt)
        // 4. 桌面上那条得跟着消失/翻到下一次。漏了只是显示旧数据，不影响触发
        WidgetUpdater.refresh(context)
    }
}
