package com.abc.daodian.reminder.domain

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** 闸门的判定 */
sealed interface Verdict {
    /** 放行。rrule 超出支持子集时已经降级成一次性 */
    data class Ok(val plan: ReminderPlan) : Verdict

    /**
     * 拦下。[reason] 写给模型看，由它转述给用户。
     * [selfFix] = 是它自己填错了、用户的话没有歧义（提前提醒排到了那天以后）：让它改好再调一次，不要拿去问用户
     */
    data class Rejected(val reason: String, val selfFix: Boolean = false) : Verdict
}

/**
 * 校验闸门。见 DESIGN.md §6.4
 *
 * 这道闸门比换更强的模型值钱得多：它把「静默出错」变成「当场问你一句」。
 */
object PlanValidator {

    private const val MAX_FUTURE_YEARS = 5L

    fun validate(plan: ReminderPlan, now: ZonedDateTime): Verdict {
        val at = runCatching { OffsetDateTime.parse(plan.firstTriggerAt).toInstant() }
            .getOrElse {
                return Verdict.Rejected("时间解析不了：${plan.firstTriggerAt}")
            }

        // 当天事项只看日期：「今天把报销交了」的 00:00 早就过了，那不是错
        if (plan.allDay) {
            val day = dueDayOf(plan)
            if (day.isBefore(now.toLocalDate())) {
                return Verdict.Rejected(
                    "算出来是 ${day.monthValue}月${day.dayOfMonth}日，已经过去了（依据：${plan.basis}），要问用户是指哪天。"
                )
            }
        } else if (!at.isAfter(now.toInstant())) {
            // 最常见的错误形态：模型算出了一个过去的时间
            return Verdict.Rejected(
                "算出来的时间在过去了（${plan.firstTriggerAt}，依据：${plan.basis}），要问用户是指什么时候。"
            )
        }
        if (at.isAfter(now.plusYears(MAX_FUTURE_YEARS).toInstant())) {
            return Verdict.Rejected(
                "算出来是 ${MAX_FUTURE_YEARS} 年以后（${plan.firstTriggerAt}），要跟用户确认是不是算错了。"
            )
        }
        if (plan.title.isBlank()) {
            return Verdict.Rejected("title 是空的")
        }

        // rrule 超出支持子集就降级成一次性，不静默丢掉
        val cleaned = if (plan.rrule != null && !Rrule.isSupported(plan.rrule)) {
            plan.copy(rrule = null)
        } else plan

        return nudges(cleaned, now.toLocalDate())
    }

    /**
     * 提前提醒那几天（§4.4）。今天以前的、那天当天的悄悄去掉（当晚本来就会提醒；模型抄旧值时会带着已经过去的）；
     * 写不成日期的、排在那天以后的、太多的拦下 —— 那是算错了。
     */
    private fun nudges(plan: ReminderPlan, today: LocalDate): Verdict {
        if (plan.remindOn.isEmpty()) return Verdict.Ok(plan)
        if (!plan.allDay || plan.rrule != null) {
            return Verdict.Rejected(
                "remindOn（提前提醒）只能加在不重复的当天事项上：allDay 要是 true、rrule 要是 null。别的提醒把 remindOn 填成 []。", selfFix = true
            )
        }
        val due = dueDayOf(plan)
        val days = plan.remindOn.map {
            runCatching { LocalDate.parse(it.trim()) }.getOrElse { _ -> return Verdict.Rejected("remindOn 里的「$it」不是 YYYY-MM-DD。", selfFix = true) }
        }
        days.firstOrNull { it.isAfter(due) }?.let {
            return Verdict.Rejected(
                "remindOn 里的 $it 排在 firstTriggerAt 那天（$due）以后了。firstTriggerAt 要填事情本身的那一天" +
                    "（过期的那天、演出的那天），不是第一次提醒的日子；remindOn 是那天之前的几天。", selfFix = true
            )
        }
        val kept = days.filter { !it.isBefore(today) && it.isBefore(due) }.distinct().sorted()
        if (kept.size > MAX_NUDGES) {
            return Verdict.Rejected("提前提醒排了 ${kept.size} 次，太多了（最多 $MAX_NUDGES 次）。问用户要不要稀一点。")
        }
        return Verdict.Ok(plan.copy(remindOn = kept.map { it.toString() }))
    }

    private const val MAX_NUDGES = 60

    /** 校验通过后，把 plan 转成本地时区下的触发时刻 */
    fun triggerMillis(plan: ReminderPlan): Long =
        OffsetDateTime.parse(plan.firstTriggerAt).toInstant().toEpochMilli()

    /** 当天事项的那一天。取模型写的日期本身（它带的偏移就是用户的），不换算时区 —— 换算会把 00:00 挪到前一天 */
    fun dueDayOf(plan: ReminderPlan): LocalDate = OffsetDateTime.parse(plan.firstTriggerAt).toLocalDate()
}
