package com.abc.daodian.harness.builtin.reminder

import com.abc.daodian.recur.Rrule
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 闸门的判定 */
sealed interface Verdict {
    /** 放行。rrule 超出支持子集时已经降级成一次性 */
    data class Ok(val plan: ReminderPlan) : Verdict

    /** 拦下。[reason] 写给模型看，由它转述给用户 */
    data class Rejected(val reason: String) : Verdict
}

/**
 * 校验闸门。见 DESIGN.md §6.5
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

        return Verdict.Ok(cleaned)
    }

    /** 校验通过后，把 plan 转成本地时区下的触发时刻 */
    fun triggerMillis(plan: ReminderPlan): Long =
        OffsetDateTime.parse(plan.firstTriggerAt).toInstant().toEpochMilli()

    /** 当天事项的那一天。取模型写的日期本身（它带的偏移就是用户的），不换算时区 —— 换算会把 00:00 挪到前一天 */
    fun dueDayOf(plan: ReminderPlan): LocalDate = OffsetDateTime.parse(plan.firstTriggerAt).toLocalDate()

    fun localTimeOf(plan: ReminderPlan, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(triggerMillis(plan)), zone)
            .toLocalTime().withSecond(0).withNano(0).toString()
}
