package com.abc.daodian.update

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 每天后台查更新的下一个钟点（09:00 / 13:00 / 19:00）。见 DESIGN.md §11.2 */
class DailyCheckTest {

    private fun at(s: String) = ZonedDateTime.parse("$s+08:00[Asia/Shanghai]")

    @Test
    fun `before the first slot is today's first slot`() {
        assertEquals(at("2026-10-16T09:00:00"), DailyCheck.next(at("2026-10-16T00:30:00")))
    }

    @Test
    fun `on a slot goes to the next one`() {
        assertEquals(at("2026-10-16T13:00:00"), DailyCheck.next(at("2026-10-16T09:00:00")))
        assertEquals(at("2026-10-16T19:00:00"), DailyCheck.next(at("2026-10-16T13:00:00")))
    }

    @Test
    fun `between slots is the later one`() {
        assertEquals(at("2026-10-16T13:00:00"), DailyCheck.next(at("2026-10-16T12:59:59")))
    }

    @Test
    fun `after the last slot is tomorrow's first`() {
        assertEquals(at("2026-10-17T09:00:00"), DailyCheck.next(at("2026-10-16T19:00:00")))
        assertEquals(at("2026-10-17T09:00:00"), DailyCheck.next(at("2026-10-16T23:59:00")))
    }
}
