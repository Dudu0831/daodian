package com.abc.daodian.reminder.domain

/** 模型给出的一条提醒，`create_reminder` 的参数。见 DESIGN.md §6.4 */
data class ReminderPlan(
    val title: String,
    val note: String? = null,
    /** ISO-8601 带偏移，如 "2026-09-02T15:00:00+08:00" */
    val firstTriggerAt: String,
    /** 模型的推算依据，如 "now + 5d, 15:00"。刻意要求它写出来，出错时能一眼看出哪儿想歪了 */
    val basis: String = "",
    val rrule: String? = null,
    val wallClockAnchored: Boolean = true,
    /**
     * 当天事项：只说了哪天、没说几点。这时 [firstTriggerAt] 只取日期，钟点换成设置里的收尾时刻。
     * 见 DESIGN.md §4.3
     */
    val allDay: Boolean = false,
    /** 轻提醒：到点只发一条通知，响一声，不响铃、不弹全屏。只有他明说了才是 true；当天事项不看它。见 DESIGN.md §5.6 */
    val gentle: Boolean = false,
    /**
     * 提前提醒：那天之前还要提醒的几天，`YYYY-MM-DD`。只有不重复的当天事项能有；过了闸门的已经去重、排好、
     * 去掉了今天以前的和那天当天的。见 DESIGN.md §4.4
     */
    val remindOn: List<String> = emptyList(),
    /** 到了那天没点完成，要不要每晚接着提醒。false = 响过那一晚就结束（只是个日子的）。只有不重复的当天事项看它 */
    val carryOver: Boolean = true
)
