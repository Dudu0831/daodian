package com.abc.daodian.ai

import com.abc.daodian.recur.Rrule
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 校验闸门。见 DESIGN.md §6.5
 *
 * 这道闸门比换更强的模型值钱得多：它把「静默出错」变成「当场问你一句」。
 */
object PlanValidator {

    private const val MAX_FUTURE_YEARS = 5L
    private const val MIN_CONFIDENCE = 0.6

    fun validate(plan: ReminderPlan, now: ZonedDateTime): ParseResult {
        val at = runCatching { OffsetDateTime.parse(plan.firstTriggerAt).toInstant() }
            .getOrElse {
                return ParseResult.Failed("时间解析不了：${plan.firstTriggerAt}", it)
            }

        // 最常见的错误形态：模型算出了一个过去的时间
        if (!at.isAfter(now.toInstant())) {
            return ParseResult.NeedsClarification(
                "算出来的时间在过去了（${plan.firstTriggerAt}，依据：${plan.basis}），你是指什么时候？",
                plan.firstTriggerAt
            )
        }
        if (at.isAfter(now.plusYears(MAX_FUTURE_YEARS).toInstant())) {
            return ParseResult.NeedsClarification(
                "算出来是 ${MAX_FUTURE_YEARS} 年以后（${plan.firstTriggerAt}），确定吗？",
                plan.firstTriggerAt
            )
        }
        if (plan.title.isBlank()) {
            return ParseResult.Failed("模型没给出 title")
        }
        plan.clarifyingQuestion?.takeIf { it.isNotBlank() }?.let {
            return ParseResult.NeedsClarification(it, plan.firstTriggerAt)
        }
        if (plan.confidence < MIN_CONFIDENCE) {
            return ParseResult.NeedsClarification(
                "不太确定（confidence=${plan.confidence}，依据：${plan.basis}），确认一下时间？",
                plan.firstTriggerAt
            )
        }

        // rrule 超出支持子集就降级成一次性，不静默丢掉
        val cleaned = if (plan.rrule != null && !Rrule.isSupported(plan.rrule)) {
            plan.copy(rrule = null)
        } else plan

        return ParseResult.Ok(cleaned)
    }

    /** 校验通过后，把 plan 转成本地时区下的触发时刻 */
    fun triggerMillis(plan: ReminderPlan): Long =
        OffsetDateTime.parse(plan.firstTriggerAt).toInstant().toEpochMilli()

    fun localTimeOf(plan: ReminderPlan, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(triggerMillis(plan)), zone)
            .toLocalTime().withSecond(0).withNano(0).toString()
}
