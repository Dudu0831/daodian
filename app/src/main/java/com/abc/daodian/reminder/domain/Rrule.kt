package com.abc.daodian.reminder.domain

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * RFC 5545 的一个**子集**。超出子集的规则一律当成一次性提醒处理。
 * 见设计文档 §7.2 —— M3 的正式实现会补全 COUNT/UNTIL 的持久化计数，
 * M1 先保证「有 rrule 的提醒响完能自己排下一次」这条链路是通的。
 */
object Rrule {

    /** 支持的键：FREQ / INTERVAL / BYDAY / BYMONTHDAY / UNTIL */
    fun isSupported(rrule: String?): Boolean {
        if (rrule.isNullOrBlank()) return false
        val parts = parse(rrule)
        val freq = parts["FREQ"] ?: return false
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return false
        return parts.keys.all { it in setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "UNTIL", "COUNT") }
    }

    /**
     * 算出 [from] 之后的下一次触发时刻。返回 null 表示序列结束或规则不支持。
     * 时刻（时分秒）沿用 [from]。
     */
    fun nextAfter(rrule: String?, from: ZonedDateTime, zone: ZoneId): ZonedDateTime? {
        if (!isSupported(rrule)) return null
        val p = parse(rrule!!)
        val interval = p["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val until = p["UNTIL"]?.let { runCatching { ZonedDateTime.parse(it).withZoneSameInstant(zone) }.getOrNull() }

        val next: ZonedDateTime? = when (p["FREQ"]) {
            "DAILY" -> from.plusDays(interval.toLong())

            "WEEKLY" -> {
                val days = p["BYDAY"]?.split(",")?.mapNotNull { toDayOfWeek(it) }?.toSortedSet()
                if (days.isNullOrEmpty()) from.plusWeeks(interval.toLong())
                else generateSequence(from.plusDays(1)) { it.plusDays(1) }
                    .take(7 * interval + 7)
                    .firstOrNull { it.dayOfWeek in days }
            }

            "MONTHLY" -> {
                val candidate = from.plusMonths(interval.toLong())
                val length = candidate.toLocalDate().lengthOfMonth()
                val byDay = p["BYMONTHDAY"]?.toIntOrNull()?.takeIf { it in 1..31 || it in -31..-1 }
                val target = when {
                    byDay == null -> from.dayOfMonth
                    // 负数从月底倒着数（RFC 5545）：-1 = 最后一天。模型把「每月月底」写成这样，
                    // 以前拿 -1 直接去 withDayOfMonth，响完那次排下一次时会抛异常
                    byDay < 0 -> (length + byDay + 1).coerceAtLeast(1)
                    else -> byDay
                }
                // 31 号落在只有 30 天的月份 → 顺延到该月最后一天，不跳过该月。
                // 这条规则必须和提示词里写的完全一致，见设计文档 §7.2
                candidate.withDayOfMonth(target.coerceAtMost(length))
            }

            "YEARLY" -> from.plusYears(interval.toLong())

            else -> null
        }

        return next?.takeIf { until == null || !it.isAfter(until) }
    }

    /** 规则 → 「每周二」这类人话。超出 §7.2 支持范围的一律显示「重复」；不重复是 null */
    fun human(rrule: String?): String? {
        if (rrule.isNullOrBlank()) return null
        val parts = parse(rrule)
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return when (parts["FREQ"]) {
            "DAILY" -> if (interval == 1) "每天" else "每 $interval 天"
            "WEEKLY" -> {
                val days = parts["BYDAY"]?.split(",")?.mapNotNull { dayName[it.trim().uppercase()] }
                when {
                    !days.isNullOrEmpty() -> "每周" + days.joinToString("、")
                    interval == 1 -> "每周"
                    else -> "每 $interval 周"
                }
            }
            "MONTHLY" -> {
                val n = parts["BYMONTHDAY"]?.toIntOrNull()
                // 负数从月底倒着数：-1 是最后一天
                val day = when {
                    n == null -> null
                    n == -1 -> "最后一天"
                    n < 0 -> "倒数第 ${-n} 天"
                    else -> " $n 号"
                }
                when {
                    day != null && interval == 1 -> "每月$day"
                    day != null -> "每 $interval 月的$day"
                    else -> "每月"
                }
            }
            "YEARLY" -> "每年"
            else -> "重复"
        }
    }

    private val dayName = mapOf(
        "MO" to "一", "TU" to "二", "WE" to "三", "TH" to "四", "FR" to "五", "SA" to "六", "SU" to "日"
    )

    private fun parse(rrule: String): Map<String, String> =
        rrule.removePrefix("RRULE:")
            .split(";")
            .mapNotNull { chunk ->
                val i = chunk.indexOf('=')
                if (i <= 0) null else chunk.substring(0, i).uppercase() to chunk.substring(i + 1)
            }
            .toMap()

    private fun toDayOfWeek(code: String): DayOfWeek? = when (code.trim().uppercase()) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }
}
