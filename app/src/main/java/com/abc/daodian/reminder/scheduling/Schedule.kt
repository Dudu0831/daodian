package com.abc.daodian.reminder.scheduling

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.clockTimes
import com.abc.daodian.reminder.data.remindBeforeDays
import com.abc.daodian.reminder.data.slotTime
import com.abc.daodian.reminder.data.untimed
import com.abc.daodian.reminder.domain.Rrule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 排着的一下：哪一次（[day] [slot]）的哪一种（[phase]），第几次催（[nagCount]），哪一刻（[at]） */
data class Beat(val day: LocalDate, val slot: LocalTime?, val phase: Phase, val nagCount: Int, val at: Long)

/**
 * 一条提醒「下一下排在哪」。DESIGN.md §4.6 那张表全在这里，纯函数：不碰 Android、不碰库、不读设置 ——
 * 收尾时刻 [check] 和时区由调用方给。所有组合在 `ScheduleTest` 里穷举。
 *
 * 一条提醒是一串「次」：从 [Reminder.day] 起照 [Reminder.rrule] 往后排的每一天，每天 [Reminder.times] 里的每个钟点
 * （不定钟点的一天一次，在收尾时刻）。每一次有几下：那天之前提一下（[Reminder.remindBefore]，只挂在那天头一个钟点上）、
 * 正点、没点完成时的催。任何时候只排一下，调度层只看它的时刻（§05 一行不改）。
 */
object Schedule {

    /** 「过一会儿再提」隔多久、最多几次（用户 11-16 定的，先写死） */
    const val NAG_SOON_MS = 30 * 60 * 1000L
    const val NAG_SOON_MAX = 3

    /** 一串最多往后数这么多次：规则写坏了也不会死循环。每天 12 个钟点也够看两年 */
    private const val GUARD = 10_000

    /**
     * 算钟点用的时区：有钟点又固定那一瞬间的（「11 月 17 日 18:00 的会」）按建它时的时区；
     * 跟着人走的、不定钟点的按现在所在的 [here]（§7.1）
     */
    fun zoneOf(r: Reminder, here: ZoneId = ZoneId.systemDefault()): ZoneId =
        if (!r.untimed && !r.wallClockAnchored) runCatching { ZoneId.of(r.zoneId) }.getOrDefault(here) else here

    /**
     * 新建、改完之后，从这条的这一次（[Reminder.day] [Reminder.slot]）起排第一下：第一个还没过的。
     *
     * 这一次的几下全过了（晚上十点才说「今天把报销交了」）：不定钟点的今天这次挪到下一个收尾时刻，事情还算今天的；
     * 拖着的（那天已经过了）设了每天再提的，下一个收尾时刻接着催。别的往后找下一次。
     * null = 一下都排不出来（一次性的、时刻全在过去），调用方自己兜底。
     */
    fun start(r: Reminder, now: Long, check: LocalTime, zone: ZoneId): Beat? {
        val occurrences = occurrences(r, Occ(r.day, r.slotTime()), check, zone).iterator()
        if (!occurrences.hasNext()) return null
        val first = occurrences.next()
        beatsOf(r, first, check, zone).firstOrNull { it.at > now }?.let { return it }
        val later = firstBeat(r, occurrences.asSequence(), now, check, zone)
        if (first.slot == null) {
            val today = dateOf(now, zone)
            val bumped = checkAfter(now, check, zone)
            val keep = when {
                !first.day.isBefore(today) -> Beat(first.day, null, Phase.MAIN, 0, bumped)
                r.nag == Nag.DAILY -> Beat(first.day, null, Phase.NAG, maxOf(r.nagCount, 1), bumped)
                else -> null
            }
            if (keep != null && (later == null || keep.at < later.at)) return keep
        }
        return later
    }

    /** 这条第一次的正点，过没过都算。[start] 排不出来时兜底用：排在过去，巡检当场补发，不会悄悄丢掉 */
    fun firstMain(r: Reminder, check: LocalTime, zone: ZoneId): Beat {
        val o = Occ(r.day, r.slotTime() ?: r.clockTimes().firstOrNull())
        return Beat(o.day, o.slot, Phase.MAIN, 0, mainAt(o, check, zone))
    }

    /**
     * 排着的那一下（[Reminder.nextTriggerAt]、[Reminder.phase]）刚响完，下一下排在哪。null = 一次性的走完了。
     * [firedAt] 是真响的时刻：补发晚到的，过了的那几下不再补，从现在往后排。
     */
    fun afterFire(r: Reminder, firedAt: Long, check: LocalTime, zone: ZoneId): Beat? {
        val here = Occ(r.day, r.slotTime())
        return when (r.phase) {
            Phase.NUDGE -> {
                // 补发晚到连正点都过了：当正点响过
                if (mainAt(here, check, zone) <= firedAt) afterMain(r, here, firedAt, check, zone)
                else beatsOf(r, here, check, zone).first { it.at > maxOf(firedAt, r.nextTriggerAt) }
            }
            Phase.MAIN -> afterMain(r, here, firedAt, check, zone)
            Phase.NAG -> afterNag(r, here, firedAt, check, zone)
        }
    }

    /** 这一次点了完成：排下一次（先排它的提前那几下）。null = 一次性的，办完了 */
    fun afterDone(r: Reminder, now: Long, check: LocalTime, zone: ZoneId): Beat? =
        firstBeat(r, occurrences(r, Occ(r.day, r.slotTime()), check, zone).drop(1), now, check, zone)

    /**
     * 改了收尾时刻、换了时区：这一下重新落在哪。null = 不用动（稍后着的、隔 30 分钟催的是绝对时刻；排不出来的留给巡检；
     * 已经到点还没响的也不动 —— 它的闹钟正要响或者巡检会补发，这里挪走就把这一下悄悄吞了）。
     * 每天再提的挪到下一个收尾时刻 —— 今晚提过了、又把收尾时刻往后改，今晚会按新钟点再提一次，改设置的人要的就是这个
     */
    fun realign(r: Reminder, now: Long, check: LocalTime, zone: ZoneId): Beat? = when {
        r.nextTriggerAt <= now -> null
        r.snoozed -> null
        r.phase == Phase.NAG && r.nag == Nag.SOON -> null
        r.phase == Phase.NAG -> Beat(r.day, r.slotTime(), Phase.NAG, r.nagCount, checkAfter(now, check, zone))
        else -> start(r, now, check, zone)
    }

    private data class Occ(val day: LocalDate, val slot: LocalTime?)

    /** 正点响过：要催的、催得比下一次早的，排催；不然排下一次 */
    private fun afterMain(r: Reminder, here: Occ, firedAt: Long, check: LocalTime, zone: ZoneId): Beat? {
        // 稍后过的、补发晚到的，从真响的那一刻往后算
        val base = maxOf(firedAt, mainAt(here, check, zone))
        val nag = when {
            overtaken(r, here, firedAt, check, zone) -> null
            r.nag == Nag.NONE -> null
            r.nag == Nag.SOON -> Beat(here.day, here.slot, Phase.NAG, 1, base + NAG_SOON_MS)
            else -> Beat(here.day, here.slot, Phase.NAG, 1, checkAfter(base, check, zone))
        }
        return earlier(nag, nextOccurrence(r, here, firedAt, check, zone))
    }

    private fun afterNag(r: Reminder, here: Occ, firedAt: Long, check: LocalTime, zone: ZoneId): Beat? {
        val base = maxOf(firedAt, r.nextTriggerAt)
        val nag = when {
            overtaken(r, here, firedAt, check, zone) -> null
            r.nag == Nag.NONE -> null
            r.nag == Nag.SOON -> if (r.nagCount < NAG_SOON_MAX) Beat(here.day, here.slot, Phase.NAG, r.nagCount + 1, base + NAG_SOON_MS) else null
            else -> Beat(here.day, here.slot, Phase.NAG, r.nagCount + 1, checkAfter(base, check, zone))
        }
        return earlier(nag, nextOccurrence(r, here, firedAt, check, zone))
    }

    /**
     * 下一次（连它的提前提醒）在这一下响之前就已经到了：手机关了几天、补发晚到时会这样。
     * 这一次不再催 —— 催到下一次来了就停，下一次早来过了
     */
    private fun overtaken(r: Reminder, here: Occ, firedAt: Long, check: LocalTime, zone: ZoneId): Boolean {
        val next = occurrences(r, here, check, zone).drop(1).firstOrNull() ?: return false
        return beatsOf(r, next, check, zone).first().at <= firedAt
    }

    /** 催到下一次（或下一次的提前提醒）来了就停 */
    private fun earlier(nag: Beat?, next: Beat?): Beat? = if (nag != null && (next == null || nag.at < next.at)) nag else next

    private fun nextOccurrence(r: Reminder, here: Occ, after: Long, check: LocalTime, zone: ZoneId): Beat? =
        firstBeat(r, occurrences(r, here, check, zone).drop(1), after, check, zone)

    private fun firstBeat(r: Reminder, occurrences: Sequence<Occ>, after: Long, check: LocalTime, zone: ZoneId): Beat? {
        for (o in occurrences) beatsOf(r, o, check, zone).firstOrNull { it.at > after }?.let { return it }
        return null
    }

    /** 这一次的几下，从早到晚：提前那几下（只挂在那天头一个钟点上，都在收尾时刻），再是正点 */
    private fun beatsOf(r: Reminder, o: Occ, check: LocalTime, zone: ZoneId): List<Beat> {
        val main = Beat(o.day, o.slot, Phase.MAIN, 0, mainAt(o, check, zone))
        if (o.slot != r.clockTimes().firstOrNull()) return listOf(main)
        val nudges = r.remindBeforeDays().map { k -> Beat(o.day, o.slot, Phase.NUDGE, 0, at(o.day.minusDays(k.toLong()), check, zone)) }
        return (nudges + main).sortedBy { it.at }
    }

    /**
     * 从 [from] 起（含）往后的每一次。[from] 那天只从它的钟点起；钟点不在 [Reminder.times] 里了（改过）就从下一个更晚的起。
     * 规则超出 §7.2 的子集时 [Rrule.nextAfter] 是 null，就当不重复 —— 同以前
     */
    private fun occurrences(r: Reminder, from: Occ, check: LocalTime, zone: ZoneId): Sequence<Occ> = sequence {
        val slots: List<LocalTime?> = r.clockTimes().ifEmpty { listOf(null) }
        var day: LocalDate? = from.day
        var first = true
        var guard = 0
        while (day != null && guard < GUARD) {
            for (s in slots) {
                if (first && from.slot != null && s != null && s < from.slot) continue
                guard++
                yield(Occ(day, s))
            }
            first = false
            val next = r.rrule?.let { Rrule.nextAfter(it, ZonedDateTime.of(day, slots.first() ?: check, zone), zone)?.toLocalDate() }
            day = next?.takeIf { it.isAfter(day) }
        }
    }

    private fun mainAt(o: Occ, check: LocalTime, zone: ZoneId): Long = at(o.day, o.slot ?: check, zone)

    private fun at(day: LocalDate, time: LocalTime, zone: ZoneId): Long = ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli()

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** [t] 之后（不含）的第一个收尾时刻 */
    private fun checkAfter(t: Long, check: LocalTime, zone: ZoneId): Long {
        var d = dateOf(t, zone)
        while (true) {
            val a = at(d, check, zone)
            if (a > t) return a
            d = d.plusDays(1)
        }
    }
}
