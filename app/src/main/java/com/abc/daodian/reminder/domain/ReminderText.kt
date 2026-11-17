package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.shared.format.Format
import java.time.LocalDate
import java.time.LocalTime

/** 提醒专用的人话写法。通用的（钟点、日期、星期）在 shared/format/Format，重复规则在 [Rrule.human] */
object ReminderText {

    /**
     * 当天事项离今天多远：「今天之内」「明天之内」「9月20日之内」；已经过了就是「拖了 2 天」。
     * 列表、痕、小组件口径一致
     */
    fun dayTaskWhen(due: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = due.toEpochDay() - today.toEpochDay()
        return when {
            days < 0 -> "拖了 ${-days} 天"
            days == 0L -> "今天之内"
            days == 1L -> "明天之内"
            days == 2L -> "后天之内"
            days in 3..6 -> Format.weekday(due.dayOfWeek) + "之内"
            else -> "${due.monthValue}月${due.dayOfMonth}日之内"
        }
    }

    /** 「09:00、15:00」；不定的是 null */
    fun clocks(times: List<LocalTime>): String? = times.takeIf { it.isNotEmpty() }?.joinToString("、") { it.toString().take(5) }

    /**
     * 什么时候，一行人话：「每天 09:00、15:00」「每周六 · 当天」「11月20日 周五 09:00」「2月13日 周六 · 当天之内」。
     * 对话里的痕、回给模型的那句都用它
     */
    fun whenOf(spec: ReminderSpec, today: LocalDate = LocalDate.now()): String {
        val repeat = Rrule.human(spec.rrule)
        val clocks = clocks(spec.times)
        return when {
            repeat != null -> if (clocks != null) "$repeat $clocks" else "$repeat · 当天"
            clocks != null -> "${Format.humanDay(spec.day, today)} $clocks"
            else -> "${Format.humanDay(spec.day, today)} · 当天之内"
        }
    }

    /** 回给模型的那一整句：哪天几点、重复、提前、怎么响、催不催，六件事都说到，它好对着用户的话核一遍 */
    fun describe(spec: ReminderSpec, today: LocalDate = LocalDate.now()): String =
        "「${spec.title}」，" + listOfNotNull(
            "${spec.day}" + (clocks(spec.times)?.let { " $it" } ?: " 不定钟点（那天晚上收尾时刻提醒）"),
            Rrule.human(spec.rrule)?.let { "重复：$it" } ?: "不重复",
            spec.remindBefore.takeIf { it.isNotEmpty() }?.let { "提前 ${it.joinToString("、")} 天提一下" },
            if (spec.ring == Ring.ALARM) "响铃" else "只发通知",
            when (spec.nag) {
                Nag.NONE -> "不催"
                Nag.SOON -> "没点完成 30 分钟后再提，最多 3 次"
                Nag.DAILY -> "没点完成每晚再提"
            },
            "固定这一瞬间（不跟时区走）".takeIf { spec.times.isNotEmpty() && !spec.wallClockAnchored }
        ).joinToString("，") + "。"
}
