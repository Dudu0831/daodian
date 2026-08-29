package com.abc.daodian.recur

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
                val target = p["BYMONTHDAY"]?.toIntOrNull() ?: from.dayOfMonth
                val candidate = from.plusMonths(interval.toLong())
                // 31 号落在只有 30 天的月份 → 顺延到该月最后一天，不跳过该月。
                // 这条规则必须和提示词里写的完全一致，见设计文档 §7.2
                candidate.withDayOfMonth(target.coerceAtMost(candidate.toLocalDate().lengthOfMonth()))
            }

            "YEARLY" -> from.plusYears(interval.toLong())

            else -> null
        }

        return next?.takeIf { until == null || !it.isAfter(until) }
    }

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
