package com.abc.daodian.reminder.presentation.alarm

import com.abc.daodian.shared.format.Format
import java.time.Instant
import java.time.ZoneId

/** 到点全屏页的日期：「九月二日 · 周三」。汉字数字只用在这一屏，其余地方一律阿拉伯数字 */
internal fun chineseDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val z = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return "${cnNumber(z.monthValue)}月${cnNumber(z.dayOfMonth)}日 · ${Format.weekday(z.dayOfWeek)}"
}

private val cnDigits = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

/** 1..31 的汉字写法，够用就行，不做通用数字转换 */
private fun cnNumber(n: Int): String = when {
    n <= 10 -> cnDigits[n]
    n < 20 -> "十" + cnDigits[n - 10]
    n % 10 == 0 -> cnDigits[n / 10] + "十"
    else -> cnDigits[n / 10] + "十" + cnDigits[n % 10]
}
