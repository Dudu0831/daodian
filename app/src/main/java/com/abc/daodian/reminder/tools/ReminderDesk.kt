package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.runtime.tool.ToolContext

/**
 * 助手的几个提醒工具背后要的：读全部、建、改、删。真机上是 `Reminders`（`reminder/assistant/ReminderPlugin` 接的），
 * 测试里是假的 —— 工具不要 Context。落库、排闹钟都在 `Reminders` 那一个入口，这里不碰 scheduling / data。
 */
interface ReminderDesk {

    /** 库里全部提醒（不分状态） */
    suspend fun all(): List<Reminder>

    /** 过了闸门的一条，落库排期，返回新 id */
    suspend fun create(plan: ReminderPlan, context: ToolContext): Long

    /** 把 [id] 整条换成 [plan]（过了闸门的），重新排期。没有这一条是 null，改好了返回改完的 */
    suspend fun update(id: Long, plan: ReminderPlan, context: ToolContext): Reminder?

    /** 删掉这几条（闹钟、通知一起收），返回真删了的 */
    suspend fun delete(ids: List<Long>): List<Reminder>
}
