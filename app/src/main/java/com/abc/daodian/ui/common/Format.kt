package com.abc.daodian.ui.common

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** 把时间戳和 RRULE 变成人话。见 DESIGN.md §03「时间：写人话，不要 ISO 时间戳」 */
object Format {

    private val weekday = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val dayName = mapOf(
        "MO" to "一", "TU" to "二", "WE" to "三", "TH" to "四", "FR" to "五", "SA" to "六", "SU" to "日"
    )

    fun humanDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val wd = weekday[z.dayOfWeek.value - 1]
        return "%d月%d日 %s %02d:%02d".format(z.monthValue, z.dayOfMonth, wd, z.hour, z.minute)
    }

    fun humanDateTime(isoWithOffset: String): String =
        runCatching { humanDateTime(OffsetDateTime.parse(isoWithOffset).toInstant().toEpochMilli()) }
            .getOrDefault(isoWithOffset)

    fun relative(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = epochMillis - nowMillis
        if (diff <= 0) return "已经到点"
        val mins = diff / 60_000
        return when {
            mins < 1 -> "马上"
            mins < 60 -> "$mins 分钟后"
            mins < 60 * 24 -> "${mins / 60} 小时后"
            else -> "${mins / (60 * 24)} 天后"
        }
    }

    /** RFC 5545 子集 → 「每周二」这类人话。超出 §7.2 支持范围的一律显示「重复」 */
    fun humanRrule(rrule: String?): String? {
        if (rrule.isNullOrBlank()) return null
        val parts = rrule.removePrefix("RRULE:")
            .split(";")
            .mapNotNull { chunk ->
                val i = chunk.indexOf('=')
                if (i <= 0) null else chunk.take(i).uppercase() to chunk.substring(i + 1)
            }
            .toMap()
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
                val day = parts["BYMONTHDAY"]
                when {
                    day != null && interval == 1 -> "每月 $day 号"
                    day != null -> "每 $interval 月的 $day 号"
                    else -> "每月"
                }
            }
            "YEARLY" -> "每年"
            else -> "重复"
        }
    }
}
