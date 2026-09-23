package com.abc.daodian.ui.ledger

import com.abc.daodian.harness.builtin.ledger.Direction
import java.math.BigDecimal
import java.time.Instant
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

    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 「9月22日 19:20」 */
    fun dayTime(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return "${t.monthValue}月${t.dayOfMonth}日 %02d:%02d".format(t.hour, t.minute)
    }

    /** 「9月22日 周二 19:20」 */
    fun dayWeekTime(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return "${t.monthValue}月${t.dayOfMonth}日 ${WEEK[t.dayOfWeek.value - 1]} %02d:%02d".format(t.hour, t.minute)
    }
}
