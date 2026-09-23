package com.abc.daodian.reminder.domain

import com.abc.daodian.shared.format.Format
import java.time.LocalDate

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
}
