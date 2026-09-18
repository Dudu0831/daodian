package com.abc.daodian.harness.builtin.reminder

/** 模型给出的一条提醒，`create_reminder` 的参数。见 DESIGN.md §6.3 */
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
    val allDay: Boolean = false
)
