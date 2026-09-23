package com.abc.daodian.ledger.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 账本里的日子和时刻。流水按 `day` 列（yyyyMMdd 的整数）查，模型读写的是 ISO 时刻 ——
 * 两种写法怎么互换只在这里写一份（以前工具、存储、整理、账本页各抄了一份）。
 */
object LedgerDays {

    /** 2026-09-22 → 20260922 */
    fun dayInt(d: LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    /** 20260922 → 2026-09-22 */
    fun dateOf(day: Int): LocalDate = LocalDate.of(day / 10000, day / 100 % 100, day % 100)

    fun dayOf(millis: Long, zone: ZoneId): Int = dayInt(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    /** 「2026-09-22」→ 20260922；读不出是 null */
    fun dayOf(text: String?): Int? = runCatching { LocalDate.parse(text!!.trim().take(10)).let(::dayInt) }.getOrNull()

    /** ISO 时刻，带不带偏移都认；不带的按 [zone]。只有日期的算那天中午 */
    fun instant(text: String?, zone: ZoneId): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli() }.getOrNull()
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 给模型读的时刻：「2026-09-22 19:20」 */
    fun stamp(millis: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(millis).atZone(zone))
}
