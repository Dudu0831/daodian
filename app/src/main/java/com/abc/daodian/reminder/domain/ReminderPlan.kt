package com.abc.daodian.reminder.domain

/**
 * 模型交的一条提醒，`create_reminder` / `update_reminder` 的参数原样（DESIGN.md §4.6、§6.4）。
 * 还没过闸门：日子、钟点、怎么响、催不催都是它写的字，[PlanValidator] 认得了才换成 [ReminderSpec]。
 */
data class ReminderPlan(
    val title: String,
    val note: String? = null,
    /** 模型的推算依据，如「用户时刻 11-16 周一 + 5 天」。刻意要求它写出来，出错时能一眼看出哪儿想歪了 */
    val basis: String = "",
    /** 事情本身那天（第一次），`YYYY-MM-DD` */
    val day: String,
    /** 几点，`HH:mm`；空 = 不定（那天晚上收尾时刻提醒） */
    val times: List<String> = emptyList(),
    val rrule: String? = null,
    /** 提前几天，在那天晚上收尾时刻提一下 */
    val remindBefore: List<Int> = emptyList(),
    /** `alarm` 响铃 / `notice` 只发通知（轻提醒） */
    val ring: String,
    /** `none` 不催 / `daily` 每天再提 */
    val nag: String,
    /** 有钟点的才看：true = 跟着人走的当地钟点，false = 固定那一瞬间（§7.1） */
    val wallClockAnchored: Boolean = true
)
