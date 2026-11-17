package com.abc.daodian.reminder.delivery

import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.Ring
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 通知上挂哪几颗（§4.6）：稍后谁都有，设了催才有「完成」，提前那次不挂 */
class NotificationButtonsTest {

    private fun r(times: String?, ring: Ring, nag: Nag = Nag.NONE, phase: Phase = Phase.MAIN) = Reminder(
        id = 1, title = "t", nextTriggerAt = 0, rrule = null, zoneId = "Asia/Shanghai", wallClockAnchored = true,
        day = LocalDate.parse("2026-11-20"), times = times, remindBefore = null, ring = ring,
        nag = nag, slot = times, phase = phase, nagCount = 0, createdAt = 0, updatedAt = 0
    )

    @Test
    fun `a nudge only mentions it, no buttons`() {
        assertEquals(emptyList<Button>(), buttonsOf(r(null, Ring.NOTICE, Nag.DAILY, Phase.NUDGE)))
        assertEquals(emptyList<Button>(), buttonsOf(r("09:00", Ring.ALARM, phase = Phase.NUDGE)))
    }

    @Test
    fun `a ringing one without nagging stops with okay, not done`() {
        assertEquals(listOf(Button.OK, Button.SNOOZE), buttonsOf(r("09:00", Ring.ALARM)))
    }

    @Test
    fun `a notice without nagging only snoozes, untimed ones too`() {
        assertEquals(listOf(Button.SNOOZE), buttonsOf(r("10:00", Ring.NOTICE)))
        assertEquals(listOf(Button.SNOOZE), buttonsOf(r(null, Ring.NOTICE)))
    }

    @Test
    fun `anything that nags asks for done`() {
        assertEquals(listOf(Button.DONE, Button.SNOOZE), buttonsOf(r(null, Ring.NOTICE, Nag.DAILY, Phase.NAG)))
        assertEquals(listOf(Button.DONE, Button.SNOOZE), buttonsOf(r("08:00", Ring.ALARM, Nag.SOON)))
        assertEquals(listOf(Button.DONE, Button.SNOOZE), buttonsOf(r("08:00", Ring.NOTICE, Nag.SOON)))
    }
}
