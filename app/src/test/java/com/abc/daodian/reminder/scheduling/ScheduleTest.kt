package com.abc.daodian.reminder.scheduling

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.Ring
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「下一下排在哪」（DESIGN.md §4.6）：六件事的各种组合，响完、点完成、补发晚到、改收尾时刻、换时区。
 * 拿 §4.6 方案里那几句真话当例子：开会、喝水、吃药、报销、飞机、生日、房租、牛奶、超市
 */
class ScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val check = LocalTime.of(20, 0)

    private fun ms(local: String, z: ZoneId = zone) = ZonedDateTime.of(java.time.LocalDateTime.parse(local), z).toInstant().toEpochMilli()

    private fun r(
        day: String,
        times: String? = null,
        rrule: String? = null,
        before: String? = null,
        nag: Nag = Nag.NONE,
        phase: Phase = Phase.MAIN,
        slot: String? = times?.substringBefore(','),
        nagCount: Int = 0,
        at: String = "2000-01-01T00:00:00"
    ) = Reminder(
        id = 1, title = "t", nextTriggerAt = ms(at), rrule = rrule, zoneId = zone.id, wallClockAnchored = true,
        day = LocalDate.parse(day), times = times, remindBefore = before, ring = if (times == null) Ring.NOTICE else Ring.ALARM,
        nag = nag, slot = slot, phase = phase, nagCount = nagCount, createdAt = 0, updatedAt = 0
    )

    /** 把排出来的那一下（或 null）照着 [Reminder] 落上去，接着往下走 */
    private fun Reminder.on(b: Beat) = copy(day = b.day, slot = b.slot?.toString(), phase = b.phase, nagCount = b.nagCount, nextTriggerAt = b.at)

    private fun beat(day: String, slot: String?, phase: Phase, at: String, nagCount: Int = 0) =
        Beat(LocalDate.parse(day), slot?.let { LocalTime.parse(it) }, phase, nagCount, ms(at))

    private fun start(x: Reminder, now: String) = Schedule.start(x, ms(now), check, zone)
    private fun fired(x: Reminder, at: String = "") = Schedule.afterFire(x, if (at.isEmpty()) x.nextTriggerAt else ms(at), check, zone)

    @Test
    fun `a one-time meeting rings once and is over`() {
        val meet = r("2026-11-17", "09:00")
        val b = start(meet, "2026-11-16T10:00:00")!!
        assertEquals(beat("2026-11-17", "09:00", Phase.MAIN, "2026-11-17T09:00:00"), b)
        assertNull(fired(meet.on(b)))
    }

    @Test
    fun `medicine every day at eight moves on to tomorrow`() {
        val pill = r("2026-11-17", "08:00", rrule = "FREQ=DAILY", at = "2026-11-17T08:00:00")
        assertEquals(beat("2026-11-18", "08:00", Phase.MAIN, "2026-11-18T08:00:00"), fired(pill))
        // 点了完成：今天这次算完，直接排明天
        assertEquals(beat("2026-11-18", "08:00", Phase.MAIN, "2026-11-18T08:00:00"), Schedule.afterDone(pill, ms("2026-11-17T08:05:00"), check, zone))
    }

    @Test
    fun `water every two hours walks through the day and wraps to tomorrow`() {
        val water = r("2026-11-17", "09:00,11:00,13:00,15:00,17:00,19:00,21:00", rrule = "FREQ=DAILY")
        assertEquals(beat("2026-11-17", "11:00", Phase.MAIN, "2026-11-17T11:00:00"), start(water, "2026-11-17T10:00:00"))
        val last = water.copy(slot = "21:00", nextTriggerAt = ms("2026-11-17T21:00:00"))
        assertEquals(beat("2026-11-18", "09:00", Phase.MAIN, "2026-11-18T09:00:00"), fired(last))
    }

    @Test
    fun `nagging stops when the next time of the day comes`() {
        // 一次性、两个钟点、每晚再提：9 点那次的催排在晚上，10 点那次先到 —— 不催了，排下一次
        val x = r("2026-11-17", "09:00,10:00", nag = Nag.DAILY, at = "2026-11-17T09:00:00")
        assertEquals(beat("2026-11-17", "10:00", Phase.MAIN, "2026-11-17T10:00:00"), fired(x))
    }

    @Test
    fun `an untimed errand rings tonight and comes back every night until done`() {
        val claim = r("2026-11-16", nag = Nag.DAILY)
        val main = start(claim, "2026-11-16T10:00:00")!!
        assertEquals(beat("2026-11-16", null, Phase.MAIN, "2026-11-16T20:00:00"), main)
        val nag1 = fired(claim.on(main))!!
        assertEquals(beat("2026-11-16", null, Phase.NAG, "2026-11-17T20:00:00", 1), nag1)
        assertEquals(beat("2026-11-16", null, Phase.NAG, "2026-11-18T20:00:00", 2), fired(claim.on(main).on(nag1)))
        assertNull(Schedule.afterDone(claim.on(nag1), ms("2026-11-17T21:00:00"), check, zone))
    }

    @Test
    fun `an errand said at ten at night waits for tomorrow evening but stays today's`() {
        assertEquals(beat("2026-11-16", null, Phase.MAIN, "2026-11-17T20:00:00"), start(r("2026-11-16", nag = Nag.DAILY), "2026-11-16T22:00:00"))
        // 一次性不催的也一样挪到明晚，不悄悄丢掉
        assertEquals(beat("2026-11-16", null, Phase.MAIN, "2026-11-17T20:00:00"), start(r("2026-11-16"), "2026-11-16T22:00:00"))
    }

    @Test
    fun `a repeating untimed one said late does not double up with tomorrow`() {
        // 今天这次挪到明晚 20:00 会和明天那次撞上：就排明天那次
        assertEquals(beat("2026-11-17", null, Phase.MAIN, "2026-11-17T20:00:00"), start(r("2026-11-16", rrule = "FREQ=DAILY"), "2026-11-16T22:00:00"))
    }

    @Test
    fun `a carried errand edited later keeps nagging, one that ends does not`() {
        assertEquals(beat("2026-11-11", null, Phase.NAG, "2026-11-16T20:00:00", 1), start(r("2026-11-11", nag = Nag.DAILY), "2026-11-16T10:00:00"))
        assertNull(start(r("2026-11-11"), "2026-11-16T10:00:00"))
    }

    @Test
    fun `milk is nudged on the given days before, then rings on its day`() {
        val milk = r("2027-02-13", before = "31,1", nag = Nag.DAILY)
        var x = milk.on(start(milk, "2026-11-13T10:00:00")!!)
        assertEquals(beat("2027-02-13", null, Phase.NUDGE, "2027-01-13T20:00:00"), Beat(x.day, null, x.phase, 0, x.nextTriggerAt))
        x = x.on(fired(x)!!)
        assertEquals(ms("2027-02-12T20:00:00"), x.nextTriggerAt)
        assertEquals(Phase.NUDGE, x.phase)
        x = x.on(fired(x)!!)
        assertEquals(beat("2027-02-13", null, Phase.MAIN, "2027-02-13T20:00:00"), Beat(x.day, null, x.phase, 0, x.nextTriggerAt))
        assertEquals(beat("2027-02-13", null, Phase.NAG, "2027-02-14T20:00:00", 1), fired(x))
        // 只是个日子的（春节）：那天提醒过就结束
        assertNull(fired(x.copy(nag = Nag.NONE)))
    }

    @Test
    fun `a flight at seven thirty is nudged the evenings before, then rings at its time`() {
        val flight = r("2026-12-23", "07:30", before = "7,1")
        var x = flight.on(start(flight, "2026-11-16T10:00:00")!!)
        assertEquals(ms("2026-12-16T20:00:00"), x.nextTriggerAt)
        x = x.on(fired(x)!!)
        assertEquals(ms("2026-12-22T20:00:00"), x.nextTriggerAt)
        x = x.on(fired(x)!!)
        assertEquals(beat("2026-12-23", "07:30", Phase.MAIN, "2026-12-23T07:30:00"), Beat(x.day, LocalTime.of(7, 30), x.phase, 0, x.nextTriggerAt))
        assertNull(fired(x))
    }

    @Test
    fun `a nudge sent so late that the day already rang counts as the day ringing`() {
        val nudged = r("2026-12-23", "07:30", before = "1", phase = Phase.NUDGE, at = "2026-12-22T20:00:00")
        assertNull(fired(nudged, "2026-12-23T09:00:00"))
        assertEquals(
            beat("2026-12-23", "07:30", Phase.NAG, "2026-12-23T20:00:00", 1),
            fired(nudged.copy(nag = Nag.DAILY), "2026-12-23T09:00:00")
        )
    }

    @Test
    fun `a yearly birthday is nudged a week before every year`() {
        val mum = r("2027-03-08", rrule = "FREQ=YEARLY", before = "7")
        val nudge = start(mum, "2026-11-16T10:00:00")!!
        assertEquals(beat("2027-03-08", null, Phase.NUDGE, "2027-03-01T20:00:00"), nudge)
        val main = fired(mum.on(nudge))!!
        assertEquals(beat("2027-03-08", null, Phase.MAIN, "2027-03-08T20:00:00"), main)
        assertEquals(beat("2028-03-08", null, Phase.NUDGE, "2028-03-01T20:00:00"), fired(mum.on(main)))
    }

    @Test
    fun `monthly rent nags every night until next month's nudge takes over`() {
        val rent = r("2026-12-15", rrule = "FREQ=MONTHLY;BYMONTHDAY=15", before = "3", nag = Nag.DAILY, at = "2026-12-15T20:00:00")
        assertEquals(beat("2026-12-15", null, Phase.NAG, "2026-12-16T20:00:00", 1), fired(rent))
        val lateNag = rent.copy(phase = Phase.NAG, nagCount = 27, nextTriggerAt = ms("2027-01-11T20:00:00"))
        // 明晚 1 月 12 日 20:00 正好是下个月的提前提醒：催停，排提前提醒
        assertEquals(beat("2027-01-15", null, Phase.NUDGE, "2027-01-12T20:00:00"), fired(lateNag))
        // 每天再提的每天重复：下一次总先来，从不催
        assertEquals(beat("2026-12-16", null, Phase.MAIN, "2026-12-16T20:00:00"), fired(r("2026-12-15", rrule = "FREQ=DAILY", nag = Nag.DAILY, at = "2026-12-15T20:00:00")))
    }

    @Test
    fun `saturday shopping is nudged the evening before, done early skips to next week`() {
        val shop = r("2026-11-21", rrule = "FREQ=WEEKLY;BYDAY=SA", before = "1")
        val nudge = start(shop, "2026-11-16T10:00:00")!!
        assertEquals(beat("2026-11-21", null, Phase.NUDGE, "2026-11-20T20:00:00"), nudge)
        // 提前提醒那晚就买完了：这一次算完，排下周的提前提醒
        assertEquals(beat("2026-11-28", null, Phase.NUDGE, "2026-11-27T20:00:00"), Schedule.afterDone(shop.on(nudge), ms("2026-11-20T20:30:00"), check, zone))
    }

    @Test
    fun `a sweep that fires hours late skips what was missed`() {
        val pill = r("2026-11-17", "08:00", rrule = "FREQ=DAILY", at = "2026-11-17T08:00:00")
        assertEquals(beat("2026-11-18", "08:00", Phase.MAIN, "2026-11-18T08:00:00"), fired(pill, "2026-11-17T10:00:00"))
        // 关机两天：前面几次不补，从现在往后排
        assertEquals(beat("2026-11-20", "08:00", Phase.MAIN, "2026-11-20T08:00:00"), fired(pill, "2026-11-19T09:00:00"))
    }

    @Test
    fun `a snoozed ring nags from when it really rang`() {
        // 19:55 的，稍后到 20:10 才响：今晚的收尾时刻已经过了，催排到明晚
        val x = r("2026-11-17", "19:55", nag = Nag.DAILY, at = "2026-11-17T20:10:00")
        assertEquals(beat("2026-11-17", "19:55", Phase.NAG, "2026-11-18T20:00:00", 1), fired(x))
    }

    @Test
    fun `a rule beyond the subset is treated as one-time`() {
        assertNull(fired(r("2026-11-17", "08:00", rrule = "FREQ=HOURLY", at = "2026-11-17T08:00:00")))
    }

    @Test
    fun `a one-time reminder all in the past still has a main to fall back to`() {
        val gone = r("2026-11-10", "08:00")
        assertNull(start(gone, "2026-11-16T10:00:00"))
        assertEquals(beat("2026-11-10", "08:00", Phase.MAIN, "2026-11-10T08:00:00"), Schedule.firstMain(gone, check, zone))
    }

    @Test
    fun `moving the evening time moves what sits on it, leaves the rest`() {
        val later = LocalTime.of(21, 0)
        val main = r("2026-11-16", at = "2026-11-16T20:00:00")
        assertEquals(beat("2026-11-16", null, Phase.MAIN, "2026-11-16T21:00:00"), Schedule.realign(main, ms("2026-11-16T10:00:00"), later, zone))
        // 今晚提过了、又往后改：今晚按新钟点再提一次
        val nag = r("2026-11-16", nag = Nag.DAILY, phase = Phase.NAG, nagCount = 1, at = "2026-11-17T20:00:00")
        assertEquals(beat("2026-11-16", null, Phase.NAG, "2026-11-16T21:00:00", 1), Schedule.realign(nag, ms("2026-11-16T20:30:00"), later, zone))
        // 稍后着的是绝对时刻，不动
        assertNull(Schedule.realign(main.copy(snoozed = true), ms("2026-11-16T10:00:00"), later, zone))
        // 已经到点还没响的不动：闹钟正要响、巡检会补发，挪走就把这一下吞了（11-16 模拟器上跳钟时碰到的）
        assertNull(Schedule.realign(r("2026-11-16", at = "2026-11-16T09:00:00"), ms("2026-11-16T10:00:00"), later, zone))
        // 有钟点的正点不看收尾时刻
        val meet = r("2026-11-17", "09:00", at = "2026-11-17T09:00:00")
        assertEquals(beat("2026-11-17", "09:00", Phase.MAIN, "2026-11-17T09:00:00"), Schedule.realign(meet, ms("2026-11-16T10:00:00"), later, zone))
    }

    @Test
    fun `flying to tokyo keeps eight in the morning local, a fixed meeting keeps its instant`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val pill = r("2026-11-17", "08:00", rrule = "FREQ=DAILY", at = "2026-11-17T08:00:00")
        assertEquals(tokyo, Schedule.zoneOf(pill, tokyo))
        assertEquals(ms("2026-11-17T08:00:00", tokyo), Schedule.realign(pill, ms("2026-11-16T10:00:00"), check, Schedule.zoneOf(pill, tokyo))!!.at)
        val meet = pill.copy(rrule = null, wallClockAnchored = false)
        assertEquals(zone, Schedule.zoneOf(meet, tokyo))
        assertEquals(tokyo, Schedule.zoneOf(r("2026-11-17"), tokyo))
    }

    @Test
    fun `fired days late after the next one already came, it does not nag about the old one`() {
        // 每周日、周二 10:00 交水费、每晚再提；手机关了两天，22 号（周日）那次 24 号中午才补发 —— 24 号那次早过了，不再为 22 号催
        val water = r("2026-11-22", "10:00", rrule = "FREQ=WEEKLY;BYDAY=SU,TU", nag = Nag.DAILY, at = "2026-11-22T10:00:00")
        assertEquals(beat("2026-11-29", "10:00", Phase.MAIN, "2026-11-29T10:00:00"), fired(water, "2026-11-24T12:00:00"))
        // 晚到一会儿、下一次还没来的，照催
        assertEquals(Phase.NAG, fired(water, "2026-11-22T10:05:00")!!.phase)
    }
}
