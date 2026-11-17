package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Ring
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** 闸门的判定 */
sealed interface Verdict {
    /** 放行，换成了要落库的那几件事。rrule 超出支持子集时已经降级成一次性，日子已经挪到规则上头一次 */
    data class Ok(val spec: ReminderSpec) : Verdict

    /**
     * 拦下。[reason] 写给模型看，由它转述给用户。
     * [selfFix] = 是它自己填错了、用户的话没有歧义：让它改好再调一次，不要拿去问用户
     */
    data class Rejected(val reason: String, val selfFix: Boolean = false) : Verdict
}

/**
 * 校验闸门。见 DESIGN.md §6.4、§4.6
 *
 * 这道闸门比换更强的模型值钱得多：它把「静默出错」变成「当场问你一句」。
 * 六件事随便组合，**只拦本身说不通的**：日子在过去、几年以后、标题空、提前的天数不比重复的间隔短、
 * 每天重复的还要每天再提、钟点太多。
 */
object PlanValidator {

    private const val MAX_FUTURE_YEARS = 5L
    const val MAX_TIMES = 12
    private const val MAX_REMIND_BEFORE = 60

    fun validate(plan: ReminderPlan, now: ZonedDateTime): Verdict {
        val read = parse(plan)
        if (read !is Verdict.Ok) return read
        val spec = read.spec
        val day = Rrule.firstOnOrAfter(spec.rrule, spec.day)
        val today = now.toLocalDate()
        val (times, rrule, before) = Triple(spec.times, spec.rrule, spec.remindBefore)

        if (day.isBefore(today)) {
            return if (rrule != null) Verdict.Rejected(
                "day 是 $day，已经过去了。重复的提醒 day 填今天或以后的头一次（依据：${plan.basis}）。", selfFix = true
            ) else Verdict.Rejected(
                "算出来是 ${day.monthValue}月${day.dayOfMonth}日，已经过去了（依据：${plan.basis}），要问用户是指哪天。"
            )
        }
        // 只看日期的「今天把报销交了」不算过去；一次性的、钟点全过了才是
        if (rrule == null && day == today && times.isNotEmpty() && times.none { it.isAfter(now.toLocalTime()) }) {
            return Verdict.Rejected(
                "算出来是今天 ${times.joinToString("、")}，已经过去了（依据：${plan.basis}），要问用户是指什么时候。"
            )
        }
        if (day.isAfter(today.plusYears(MAX_FUTURE_YEARS))) {
            return Verdict.Rejected("算出来是 ${MAX_FUTURE_YEARS} 年以后（$day），要跟用户确认是不是算错了。")
        }
        if (times.size > MAX_TIMES) {
            return Verdict.Rejected("一天排了 ${times.size} 个钟点，最多 $MAX_TIMES 个。问用户要不要稀一点。")
        }
        if (before.size > MAX_REMIND_BEFORE) {
            return Verdict.Rejected("提前提醒排了 ${before.size} 次，太多了（最多 $MAX_REMIND_BEFORE 次）。问用户要不要稀一点。")
        }

        // 只在本身说不通时才拦（§4.6）
        if (rrule != null) {
            val gap = Rrule.shortestGap(rrule, day, now.zone)
            if (gap != null && before.isNotEmpty() && before.first() >= gap) {
                return Verdict.Rejected(
                    "${Rrule.human(rrule)}重复，两次之间最短 $gap 天，提前 ${before.first()} 天就和上一次撞上了：提前的天数要比 $gap 天短。" +
                        "问用户是不是不用提前提醒，或者提前得少一点。"
                )
            }
            if (gap != null && gap <= 1 && spec.nag == Nag.DAILY) {
                return Verdict.Rejected(
                    "每天都有的提醒不能「每天再提」—— 明天本来就会再响。要催就填 soon（30 分钟后再提），不催填 none。", selfFix = true
                )
            }
        }
        return Verdict.Ok(spec.copy(day = day))
    }

    /** 只认字、不看日子对不对：对话里的痕照它写（建的时候过没过闸门，结果里有） */
    fun read(plan: ReminderPlan): ReminderSpec? = (parse(plan) as? Verdict.Ok)?.spec

    /** 把模型写的字认成 [ReminderSpec]；写错的格式（不是日期、不是钟点、不认得的选项）是它自己改的事 */
    private fun parse(plan: ReminderPlan): Verdict {
        if (plan.title.isBlank()) return Verdict.Rejected("title 是空的", selfFix = true)
        val day = runCatching { LocalDate.parse(plan.day.trim()) }
            .getOrElse { return Verdict.Rejected("day「${plan.day}」不是 YYYY-MM-DD。", selfFix = true) }
        val times = plan.times.map { t ->
            parseTime(t) ?: return Verdict.Rejected("times 里的「$t」不是 HH:mm。", selfFix = true)
        }.distinct().sorted()
        val ring = when (plan.ring.trim().lowercase()) {
            "alarm" -> Ring.ALARM
            "notice" -> Ring.NOTICE
            else -> return Verdict.Rejected("ring 只能是 alarm 或 notice，不是「${plan.ring}」。", selfFix = true)
        }
        val nag = when (plan.nag.trim().lowercase()) {
            "none" -> Nag.NONE
            "soon" -> Nag.SOON
            "daily" -> Nag.DAILY
            else -> return Verdict.Rejected("nag 只能是 none、soon、daily，不是「${plan.nag}」。", selfFix = true)
        }
        plan.remindBefore.firstOrNull { it < 1 }?.let {
            return Verdict.Rejected("remindBefore 里的 $it 不对：填提前几天，正整数；那天当天本来就会提醒，不用写 0。", selfFix = true)
        }
        return Verdict.Ok(
            ReminderSpec(
                title = plan.title.trim(),
                note = plan.note?.trim()?.takeIf { it.isNotEmpty() },
                day = day, times = times,
                // rrule 超出支持子集就降级成一次性，不静默丢掉
                rrule = plan.rrule?.trim()?.takeIf { Rrule.isSupported(it) },
                remindBefore = plan.remindBefore.distinct().sortedDescending(),
                // 不定钟点的收在收尾时刻那一组通知里，一律只发通知（§4.6）
                ring = if (times.isEmpty()) Ring.NOTICE else ring, nag = nag,
                // 不定钟点的一律按当地（§4.6）
                wallClockAnchored = times.isEmpty() || plan.wallClockAnchored
            )
        )
    }

    /** `HH:mm`，带秒的也认，秒扔掉 */
    private fun parseTime(t: String): LocalTime? = try {
        LocalTime.parse(t.trim()).withSecond(0).withNano(0)
    } catch (_: DateTimeParseException) {
        null
    }
}
