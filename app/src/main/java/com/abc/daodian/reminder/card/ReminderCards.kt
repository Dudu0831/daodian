package com.abc.daodian.reminder.card

import android.content.Context
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardSubject
import com.abc.daodian.reminder.ReminderModule
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.dueDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒给桌面卡片的：一条提醒可以钉到桌面上数日子（DESIGN.md §8.5）。卡片模块来问「#12 现在什么样」，这里只读库作答；
 * 提醒有改动时由 `widget/WidgetUpdater.refresh` 顺手喊卡片重画。
 */
object ReminderCards : CardSource {

    override val id = ReminderModule.ID

    override val label = ReminderModule.LABEL

    override val brief = "ref 是提醒的 # 编号，用 list_reminders 查。还没有这条（他只说了「牛奶 2 月 13 日过期，放桌面」）就先 create_reminder " +
        "建成当天事项（allDay=true，日子填那一天，remindOn 没说就 []），再拿建好的 id 钉。"

    override suspend fun subject(context: Context, ref: Long): CardSubject? =
        ReminderDatabase.get(context).reminderDao().byId(ref)?.let { subjectOf(it) }

    override fun route(ref: Long): String = ReminderRoutes.edit(ref)

    /** 说的是哪一天：当天事项是它的日子（顺延不改它），带钟点的是下一次响的那天 */
    fun subjectOf(reminder: Reminder, zone: ZoneId = ZoneId.systemDefault()): CardSubject = CardSubject(
        title = reminder.title,
        day = reminder.dueDate() ?: Instant.ofEpochMilli(reminder.nextTriggerAt).atZone(zone).toLocalDate(),
        done = reminder.status == ReminderStatus.DONE || reminder.status == ReminderStatus.CANCELLED
    )
}
