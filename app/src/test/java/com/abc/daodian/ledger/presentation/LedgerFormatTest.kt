package com.abc.daodian.ledger.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 抓取页上的时刻写法：今天 / 昨天 / 几月几号、晚了多久、怎么抓到的 */
class LedgerFormatTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 24)
    private fun at(s: String) = ZonedDateTime.parse("${s}+08:00[Asia/Shanghai]").toInstant().toEpochMilli()

    @Test
    fun `day header says today, yesterday, then the date with weekday`() {
        assertEquals("今天", LedgerFormat.dayHeader(today, today))
        assertEquals("昨天", LedgerFormat.dayHeader(today.minusDays(1), today))
        assertEquals("9月22日 周二", LedgerFormat.dayHeader(today.minusDays(2), today))
    }

    @Test
    fun `recent moments read like a person would say them`() {
        assertEquals("今天 09:05", LedgerFormat.recent(at("2026-09-24T09:05:41"), zone, today))
        assertEquals("昨天 23:59", LedgerFormat.recent(at("2026-09-23T23:59:00"), zone, today))
        assertEquals("9月20日 08:00", LedgerFormat.recent(at("2026-09-20T08:00:00"), zone, today))
        assertEquals("今天 09:05:41", LedgerFormat.recentSeconds(at("2026-09-24T09:05:41"), zone, today))
    }

    @Test
    fun `lag is seconds under a minute, then minutes and hours`() {
        assertEquals("2 秒", LedgerFormat.lag(2_400))
        assertEquals("0 秒", LedgerFormat.lag(-500))
        assertEquals("5 分钟", LedgerFormat.lag(5 * 60_000L + 10_000))
        assertEquals("3 小时 5 分", LedgerFormat.lag((3 * 60 + 5) * 60_000L))
    }

    @Test
    fun `captured how, including the old name for the pre-organize sweep`() {
        assertEquals("实时", LedgerFormat.capturedHow("posted"))
        assertEquals("整理前扫到", LedgerFormat.capturedHow("organize"))
        assertEquals("整理前扫到", LedgerFormat.capturedHow("manual"))
        assertEquals("手动抓", LedgerFormat.capturedHow("tap"))
        assertEquals("遮蔽后重读", LedgerFormat.capturedHow("retry+30s"))
        assertEquals("something-new", LedgerFormat.capturedHow("something-new"))
    }
}
