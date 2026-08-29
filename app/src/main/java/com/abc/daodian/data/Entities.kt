package com.abc.daodian.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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

    val createdAt: Long,
    val updatedAt: Long
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
