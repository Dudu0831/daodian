package com.abc.daodian.reminder.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

enum class ReminderStatus { SCHEDULED, FIRED, DONE, CANCELLED }

/** 这条提醒是怎么响的。日志里出现 SWEEP 就说明主闹钟路径正在被 ROM 掐掉，见设计文档 §4.2 */
enum class FireSource { ALARM, SWEEP, BOOT_CATCHUP, MANUAL_TEST }

/** 到点怎么响（§4.6）：响铃 = 全屏 + 闹钟铃声循环；只发通知 = 一条通知、响一声震一下（界面上叫「轻提醒」，§5.6） */
enum class Ring { ALARM, NOTICE }

/** 响过没点完成要不要催（§4.6）：不催 / 过一会儿再提（30 分钟一次，最多 3 次）/ 每天再提（每晚收尾时刻，直到点完成） */
enum class Nag { NONE, SOON, DAILY }

/** 眼下排着的这一下是哪一种（§4.6）：那天之前提一下 / 正点 / 催 */
enum class Phase { NUDGE, MAIN, NAG }

/**
 * 一条提醒。只有一种（DESIGN.md §4.6）：每条都回答同样几件事 —— 哪天（[day]）、几点（[times]，可以不定、可以几个）、
 * 重复（[rrule]）、提前几天（[remindBefore]）、到点怎么响（[ring]）、响过没点完成要不要催（[nag]）。
 *
 * 后面五个字段（[day] [slot] [phase] [nagCount] [snoozed]）是「这一次排到哪了」：[nextTriggerAt] 是哪一次的哪一下。
 * 下一下排在哪全由 [com.abc.daodian.reminder.scheduling.Schedule] 算，调度层照旧只看 [nextTriggerAt]。
 */
@Entity(
    tableName = "reminders",
    indices = [Index("nextTriggerAt"), Index("status")]
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 「交房租」—— 去掉「提醒我」这类壳 */
    val title: String,
    val note: String? = null,

    /** 你的原话。解析出错时能对照着看模型怎么想歪的；手动建的就是标题 */
    val rawInput: String = "",

    /** epoch millis。**唯一的排期依据** */
    val nextTriggerAt: Long,

    /** RFC 5545 子集（§7.2）；null = 不重复 */
    val rrule: String? = null,

    /** 建的时候所在的时区。有钟点又固定那一瞬间的（[wallClockAnchored] = false）按它算，见 §7.1 */
    val zoneId: String,

    /** true = 跟着你走的本地墙钟时间。不定钟点的不看它，一律按当地。见设计文档 §7.1 */
    val wallClockAnchored: Boolean = false,

    val status: ReminderStatus = ReminderStatus.SCHEDULED,

    /** 哪个模型解析的，便于回溯；手动建的是 null */
    val parsedBy: String? = null,

    /** 这一次是哪天。重复的每翻一次跟着走；没点完成每天再提的，它不动（拖了几天 = 今天 − 它） */
    val day: LocalDate,

    /** 几点，`"09:00,11:00"` 从早到晚，最多 12 个；null = 不定（那天晚上收尾时刻提醒，列表上写「当天」） */
    val times: String? = null,

    /** 提前几天提一下，`"30,7,1"`；null = 不提前。提前的那几次都在那天晚上收尾时刻，只挂在那天头一个钟点上 */
    val remindBefore: String? = null,

    val ring: Ring = Ring.ALARM,

    val nag: Nag = Nag.NONE,

    /** 这一次是 [times] 里的哪个钟点；不定钟点的是 null */
    val slot: String? = null,

    /** [nextTriggerAt] 是这一次的哪一下 */
    val phase: Phase = Phase.MAIN,

    /** 这一次已经催了几次（[phase] 是 [Phase.NAG] 时，排着的就是第几次） */
    val nagCount: Int = 0,

    /** 稍后着：[nextTriggerAt] 是「十分钟后」这种绝对时刻，改收尾时刻、换时区都不动它 */
    val snoozed: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long
)

/** 几点不定（只说了哪天）。界面上那一套「当天」只给它 */
val Reminder.untimed: Boolean get() = times == null

/** 不定钟点的那天；有钟点的是 null */
fun Reminder.untimedDay(): LocalDate? = if (untimed) day else null

/** 几个钟点，从早到晚；不定钟点的是空的。写坏的跳过 */
fun Reminder.clockTimes(): List<LocalTime> = parseTimes(times)

/** 这一次的钟点；不定钟点的是 null */
fun Reminder.slotTime(): LocalTime? = slot?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/** 提前几天，从多到少；写坏的跳过 */
fun Reminder.remindBeforeDays(): List<Int> =
    remindBefore?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { d -> d > 0 } }?.distinct()?.sortedDescending().orEmpty()

fun parseTimes(column: String?): List<LocalTime> =
    column?.split(',')?.mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }?.distinct()?.sorted().orEmpty()

/** [Reminder.times] 那一列怎么存：去重、从早到晚，一个都没有就是 null（不定） */
fun timesColumn(times: List<LocalTime>): String? =
    times.map { it.withSecond(0).withNano(0) }.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(",")

/** [Reminder.remindBefore] 那一列怎么存：只留正数、去重、从多到少，一个都没有就是 null */
fun remindBeforeColumn(days: List<Int>): String? =
    days.filter { it > 0 }.distinct().sortedDescending().takeIf { it.isNotEmpty() }?.joinToString(",")

/**
 * 清单里的一样（§4.5），挂在一条提醒底下。v5 先把表建好，读写在清单那一步接上。
 * 不用外键级联：提醒页左滑删了还能撤销，清单得跟着回来，删由代码管
 */
@Entity(tableName = "reminder_items", indices = [Index("reminderId")])
data class ReminderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val text: String,
    val done: Boolean = false,
    /** 排第几，从 0 起 */
    val position: Int
)

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
