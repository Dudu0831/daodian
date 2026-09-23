package com.abc.daodian.shared.format

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 把时间戳变成人话。见 DESIGN.md §03「时间：写人话，不要 ISO 时间戳」
 * 只放各模块都用得上的；重复规则、当天事项的写法在提醒模块（Rrule.human、ReminderText），到点全屏页的汉字日期在 alarm/。
 */
object Format {

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 「周五」。全 app 只这一份 */
    fun weekday(day: DayOfWeek): String = WEEKDAYS[day.value - 1]

    fun humanDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val wd = weekday(z.dayOfWeek)
        return "%d月%d日 %s %02d:%02d".format(z.monthValue, z.dayOfMonth, wd, z.hour, z.minute)
    }

    /** 当天事项的「哪天」：「9月18日 周五」，不带钟点 */
    fun humanDay(date: LocalDate): String =
        "%d月%d日 %s".format(date.monthValue, date.dayOfMonth, weekday(date.dayOfWeek))

    /** 收起态、列表这类地方用的短写法：「9月2日 15:00」，不带星期 */
    fun humanDateTimeShort(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return "%d月%d日 %02d:%02d".format(z.monthValue, z.dayOfMonth, z.hour, z.minute)
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

    /** 一段时长的人话，精确到分：「1 小时 17 分」「2 天 3 小时」。列表页「下一条」和「过点多久」用 */
    fun span(millis: Long): String {
        val mins = millis.coerceAtLeast(0) / 60_000
        return when {
            mins < 1 -> "不到 1 分钟"
            mins < 60 -> "$mins 分钟"
            mins < 60 * 24 -> if (mins % 60 == 0L) "${mins / 60} 小时" else "${mins / 60} 小时 ${mins % 60} 分"
            else -> {
                val h = mins % (60 * 24) / 60
                if (h == 0L) "${mins / (60 * 24)} 天" else "${mins / (60 * 24)} 天 $h 小时"
            }
        }
    }

    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return "%02d:%02d".format(z.hour, z.minute)
    }

    /** 空状态的招呼语，跟着一天里的时段走 */
    fun greeting(hour: Int): String = when (hour) {
        in 0..4 -> "夜深了——"
        in 5..10 -> "早上好——"
        in 11..12 -> "中午好——"
        in 13..17 -> "下午好——"
        else -> "晚上好——"
    }

    /** 网关地址只留主机名：「ark.cn-beijing.volces.com」。顶栏纸签、设置页共用 */
    fun host(baseUrl: String): String =
        baseUrl.substringAfter("://").substringBefore('/').trim()
}
