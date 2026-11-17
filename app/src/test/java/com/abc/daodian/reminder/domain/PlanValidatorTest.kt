package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Ring
import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 闸门（§4.6、§6.4）：六件事怎么组合都放行，只拦本身说不通的 */
class PlanValidatorTest {

    // 11-16 周一 11:20
    private val now = ZonedDateTime.parse("2026-11-16T11:20:00+08:00[Asia/Shanghai]")

    private fun plan(
        day: String = "2026-11-16", times: List<String> = emptyList(), rrule: String? = null,
        before: List<Int> = emptyList(), ring: String = "notice", nag: String = "none", anchored: Boolean = true
    ) = ReminderPlan(title = "t", day = day, times = times, rrule = rrule, remindBefore = before, ring = ring, nag = nag, wallClockAnchored = anchored)

    private fun ok(p: ReminderPlan) = (PlanValidator.validate(p, now) as? Verdict.Ok)?.spec
        ?: error("拦下了：${PlanValidator.validate(p, now)}")

    private fun rejected(p: ReminderPlan) = PlanValidator.validate(p, now) as? Verdict.Rejected ?: error("放行了：$p")

    @Test
    fun `any combination that makes sense goes through`() {
        // 每天 9 点到 21 点每两小时喝水、过一会儿再提
        ok(plan(times = listOf("09:00", "11:00", "13:00", "15:00", "17:00", "19:00", "21:00"), rrule = "FREQ=DAILY", nag = "soon"))
        // 每年生日提前一周
        ok(plan(day = "2027-03-08", rrule = "FREQ=YEARLY", before = listOf(7)))
        // 飞机 07:30 提前一周、前一天
        ok(plan(day = "2026-12-23", times = listOf("07:30"), before = listOf(1, 7), ring = "alarm", anchored = false))
        // 没钟点的也能重复、每周的提前 6 天
        ok(plan(day = "2026-11-21", rrule = "FREQ=WEEKLY;BYDAY=SA", before = listOf(6)))
        // 今天、只说了哪天的：00:00 早过了不算过去
        ok(plan(nag = "daily"))
    }

    @Test
    fun `what the model wrote is normalised`() {
        val s = ok(plan(times = listOf("21:00", "09:00:30", "21:00"), before = listOf(1, 7, 1), ring = " ALARM ", anchored = false))
        assertEquals("[09:00, 21:00]", s.times.toString())
        assertEquals(listOf(7, 1), s.remindBefore)
        assertFalse(s.wallClockAnchored)
        // 不定钟点的一律跟着当地
        assertTrue(ok(plan(anchored = false)).wallClockAnchored)
        // 不定钟点的一律只发通知：收在收尾时刻那一组里
        assertEquals(Ring.NOTICE, ok(plan(ring = "alarm")).ring)
        assertEquals(Ring.ALARM, ok(plan(times = listOf("21:00"), ring = "alarm")).ring)
        // 超出子集的重复降级成一次性
        assertEquals(null, ok(plan(rrule = "FREQ=HOURLY")).rrule)
    }

    @Test
    fun `a weekly one said on another day starts on its weekday`() {
        // 周一说「每周六买菜」，模型填了今天
        assertEquals(LocalDate.parse("2026-11-21"), ok(plan(rrule = "FREQ=WEEKLY;BYDAY=SA")).day)
        // 每月 5 号，今天 16 号：下个月 5 号
        assertEquals(LocalDate.parse("2026-12-05"), ok(plan(rrule = "FREQ=MONTHLY;BYMONTHDAY=5")).day)
        // 每月最后一天
        assertEquals(LocalDate.parse("2026-11-30"), ok(plan(rrule = "FREQ=MONTHLY;BYMONTHDAY=-1")).day)
    }

    @Test
    fun `the past is asked about, a repeating one in the past is the model's to fix`() {
        assertFalse(rejected(plan(day = "2026-11-15")).selfFix)
        assertFalse("今天 9 点已经过了", rejected(plan(times = listOf("09:00"))).selfFix)
        ok(plan(times = listOf("09:00", "15:00")))
        // 重复的今天 9 点过了没关系，下一次是明天
        ok(plan(times = listOf("09:00"), rrule = "FREQ=DAILY"))
        assertTrue(rejected(plan(day = "2026-11-10", rrule = "FREQ=DAILY")).selfFix)
        assertFalse(rejected(plan(day = "2032-01-01")).selfFix)
    }

    @Test
    fun `only what cannot work together is stopped`() {
        // 每天的不能提前；每周的提前 7 天和上一次撞上
        assertFalse(rejected(plan(rrule = "FREQ=DAILY", before = listOf(1))).selfFix)
        assertTrue("间隔", "最短 7 天" in rejected(plan(day = "2026-11-21", rrule = "FREQ=WEEKLY;BYDAY=SA", before = listOf(7))).reason)
        // 每天都有的不能每天再提；每周的可以
        assertTrue(rejected(plan(rrule = "FREQ=DAILY", nag = "daily")).selfFix)
        assertEquals(Nag.DAILY, ok(plan(day = "2026-11-21", rrule = "FREQ=WEEKLY;BYDAY=SA", nag = "daily")).nag)
        // 一周两次（周一、周三）：最短间隔 2 天，提前 1 天行，2 天不行
        ok(plan(day = "2026-11-18", rrule = "FREQ=WEEKLY;BYDAY=MO,WE", before = listOf(1)))
        rejected(plan(day = "2026-11-18", rrule = "FREQ=WEEKLY;BYDAY=MO,WE", before = listOf(2)))
        // 钟点最多 12 个
        rejected(plan(times = (8..20).map { "%02d:00".format(it) }, rrule = "FREQ=DAILY"))
    }

    @Test
    fun `bad words are the model's to fix`() {
        assertTrue(rejected(plan(day = "11月20日")).selfFix)
        assertTrue(rejected(plan(times = listOf("9点"))).selfFix)
        assertTrue(rejected(plan(ring = "loud")).selfFix)
        assertTrue(rejected(plan(nag = "always")).selfFix)
        assertTrue(rejected(plan(before = listOf(0))).selfFix)
        assertTrue(rejected(plan().copy(title = " ")).selfFix)
    }
}
