package com.abc.daodian.ledger.presentation

import com.abc.daodian.ledger.domain.Direction
import com.abc.daodian.shared.format.Format
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 记账页上钱和时间的写法 */
internal object LedgerFormat {

    /** 分 → 「1,234.50」。负数带「−」（真正的减号，不是连字符） */
    fun money(cents: Long): String {
        val abs = BigDecimal.valueOf(kotlin.math.abs(cents), 2)
        val text = "%,.2f".format(abs)
        return if (cents < 0) "−$text" else text
    }

    fun yuan(cents: Long): String = "¥" + money(cents)

    /** 列表里一笔的金额：收入带 +，退款带 − */
    fun signed(direction: Direction, cents: Long): String = when (direction) {
        Direction.IN -> "+" + money(cents)
        Direction.REFUND -> "−" + money(cents)
        else -> money(cents)
    }

    /** 「9月22日 19:20」 */
    fun dayTime(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return "${t.monthValue}月${t.dayOfMonth}日 %02d:%02d".format(t.hour, t.minute)
    }

    /** 下次对账是什么时候：「今晚 21:30」「今天 17:19」，今天的钟点已经过了就是「明天 21:30」 */
    fun nextCheck(time: LocalTime, now: LocalTime = LocalTime.now()): String {
        val hm = "%02d:%02d".format(time.hour, time.minute)
        return when {
            !time.isAfter(now) -> "明天 $hm"
            time.hour >= 18 -> "今晚 $hm"
            else -> "今天 $hm"
        }
    }

    /** 「9月22日 周二 19:20」 */
    fun dayWeekTime(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return "${t.monthValue}月${t.dayOfMonth}日 ${Format.weekday(t.dayOfWeek)} %02d:%02d".format(t.hour, t.minute)
    }

    // ---------------- 抓取页 ----------------

    /** 按天分组的小标题：「今天」「昨天」「9月22日 周二」 */
    fun dayHeader(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> Format.humanDay(date)
    }

    /** 「今天 10:12」「昨天 22:40」「9月20日 08:00」 */
    fun recent(millis: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) {
            today -> "今天 $hm"
            today.minusDays(1) -> "昨天 $hm"
            else -> "${t.monthValue}月${t.dayOfMonth}日 $hm"
        }
    }

    /** 「10:12:03」：通知发出和抓到差几秒，要看到秒 */
    fun clockSeconds(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
    }

    /** 「今天 10:12:03」：抓到的时刻，可能和通知发出不是同一天 */
    fun recentSeconds(millis: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        recent(millis, zone, today) + ":%02d".format(Instant.ofEpochMilli(millis).atZone(zone).second)

    /** 抓到比发出晚了多久：「2 秒」「3 小时 5 分」 */
    fun lag(millis: Long): String =
        if (millis < 60_000) "${millis.coerceAtLeast(0) / 1000} 秒" else Format.span(millis)

    /** raw_notification.capturedHow 的人话：怎么抓到的 */
    fun capturedHow(how: String): String = when {
        how == "posted" -> "实时"
        how == "active" -> "连上时扫到"
        how == "unlock" -> "解锁时扫到"
        how == "organize" || how == "manual" -> "整理前扫到"
        how == "tap" -> "手动抓"
        how.startsWith("retry+") -> "遮蔽后重读"
        how == "import" -> "导入"
        else -> how
    }
}
