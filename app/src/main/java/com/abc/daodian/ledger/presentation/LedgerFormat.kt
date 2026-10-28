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

    /**
     * 「N 笔没认出来，」后面那半句：还没问是「今晚 21:30 问你」；问过了、你还没对（[waiting]，
     * 对话页末尾那段虚线也在）是「还等你对」—— 过了点写「明天 21:30 问你」就像今晚没问过一样
     */
    fun checkNote(time: LocalTime, waiting: Boolean): String = if (waiting) "还等你对" else "${nextCheck(time)} 问你"

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
}
