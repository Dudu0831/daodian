package com.abc.daodian.reminder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class ReminderStatus { SCHEDULED, FIRED, DONE, CANCELLED }

/** 这条提醒是怎么响的。日志里出现 SWEEP 就说明主闹钟路径正在被 ROM 掐掉，见设计文档 §4.2 */
enum class FireSource { ALARM, SWEEP, BOOT_CATCHUP, MANUAL_TEST }

@Entity(
    tableName = "reminders",
    indices = [Index("nextTriggerAt"), Index("status")]
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 「交房租」—— 去掉「提醒我」这类壳 */
    val title: String,
    val note: String? = null,

    /** 你的原话。M1 阶段等于手填的标题；M2 接上 AI 后是真正的原始输入 */
    val rawInput: String = "",

    /** epoch millis。唯一的排期依据 */
    val nextTriggerAt: Long,

    /** RFC 5545 子集；null = 一次性。M3 才真正求值 */
    val rrule: String? = null,

    /** 创建时所在时区 */
    val zoneId: String,

    /** 墙钟锚定时用来重算，形如 "08:00" */
    val localTime: String? = null,

    /** true = 跟着你走的本地墙钟时间。见设计文档 §7.1 */
    val wallClockAnchored: Boolean = false,

    val status: ReminderStatus = ReminderStatus.SCHEDULED,

    /** 哪个模型解析的，便于回溯。M1 恒为 null */
    val parsedBy: String? = null,

    /**
     * 当天事项：只说了哪天、没说几点（「今天把报销交了」）。形如 "2026-09-18"；null = 普通定时提醒。
     *
     * 排期照旧只看 [nextTriggerAt] —— 它是这一天的「收尾时刻」（默认 20:00），调度层不知道也不用知道有当天事项。
     * 到点没做完顺延到第二天的收尾时刻，**这个字段不动**：「拖了几天」= 今天 − dueDay。
     * 重复的当天事项例外：一次不做就翻到下一次，dueDay 跟着走。见 DESIGN.md §4.3
     */
    val dueDay: String? = null,

    /**
     * 轻提醒：到点只发一条通知，响一声，不响铃、不弹全屏（[com.abc.daodian.reminder.delivery.Notifier] 里分叉）。false = 响铃，默认。
     *
     * 只管响法：排期照旧只看 [nextTriggerAt]，调度层不知道这一档，和它不知道当天事项一样。
     * 当天事项不看它（本来就只发通知）。见 DESIGN.md §5.6
     */
    @ColumnInfo(defaultValue = "0")
    val gentle: Boolean = false,

    /**
     * 重复的定时提醒被「稍后」时，这一串本来的下一次（epoch millis）；null = 没在稍后。
     *
     * 稍后只挪刚响的这一次：[nextTriggerAt] 换成稍后那一刻（调度层照旧只看它），这一串本来的下一次先存在这儿，
     * 稍后那次响完回到它。一次性的稍后不用它。见 [com.abc.daodian.reminder.scheduling.Series]、DESIGN.md §5.6
     */
    val seriesNextAt: Long? = null,

    /**
     * 提前提醒：日子还远的当天事项（「牛奶 2 月 13 日过期，每个月提醒一下，最后一周每天提醒」），在 [dueDay] 之前还要提醒的那几天。
     * 逗号分开的日期，从早到晚：`"2026-12-13,2027-01-13,2027-02-07"`；null = 没有。只有不重复的当天事项用它。
     *
     * 还是一条记录、一个闹钟：[nextTriggerAt] 是最近一次还没过的提前提醒（那天的收尾时刻），响完翻到下一个，
     * 都响完了就是 [dueDay] 那天 —— 从那以后和普通的当天事项一样。提前那几次响完不顺延、[dueDay] 不动。
     * 调度层照旧只看 [nextTriggerAt]。见 [com.abc.daodian.reminder.scheduling.DayTasks]、DESIGN.md §4.4
     */
    val nudges: String? = null,

    /**
     * 到了 [dueDay] 那天响过就结束，不顺延（春节、演出这种只是个日子的）。false = 没点完成就每晚接着提醒，
     * 当天事项一直以来的样子。只有不重复的当天事项看它。见 DESIGN.md §4.4
     */
    @ColumnInfo(defaultValue = "0")
    val endsOnDay: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long
)

/** 是不是当天事项（没有具体钟点、晚上收尾提醒一次、没做完顺延）。见 [Reminder.dueDay] */
val Reminder.isAllDay: Boolean get() = dueDay != null

fun Reminder.dueDate(): LocalDate? = dueDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** 提前提醒的那几天，从早到晚；写坏的跳过。见 [Reminder.nudges] */
fun Reminder.nudgeDates(): List<LocalDate> =
    nudges?.split(',')?.mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }?.sorted().orEmpty()

/** [Reminder.nudges] 那一列怎么存：去重、从早到晚，一个都没有就是 null */
fun nudgesColumn(days: List<LocalDate>): String? =
    days.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(",")

/**
 * 投递日志。这张表是 M1 的验收依据 —— 没有它，「准不准」就只是感觉。
 * 见设计文档 §4.2 与 §9.3
 */
@Entity(tableName = "fire_log", indices = [Index("firedAt")])
data class FireLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val title: String,
    /** 本该响的时刻 */
    val scheduledAt: Long,
    /** 实际响的时刻 */
    val firedAt: Long,
    val source: FireSource
) {
    /** 漂移毫秒数。正数 = 迟到 */
    val driftMillis: Long get() = firedAt - scheduledAt
}
