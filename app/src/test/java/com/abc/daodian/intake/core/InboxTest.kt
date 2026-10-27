package com.abc.daodian.intake.core

import com.abc.daodian.intake.core.inbox.Existing
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.intake.core.inbox.InboxState.DONE
import com.abc.daodian.intake.core.inbox.InboxState.FAILED
import com.abc.daodian.intake.core.inbox.InboxState.PENDING
import com.abc.daodian.intake.core.inbox.InboxState.WAITING
import com.abc.daodian.intake.core.inbox.InboxState.WORKING
import com.abc.daodian.intake.core.inbox.Landing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 收件箱一行的状态机，和一条到门口怎么落 */
class InboxTest {

    @Test
    fun `allowed moves pass`() {
        assertEquals(PENDING, WAITING.to(PENDING))
        assertEquals(WORKING, PENDING.to(WORKING))
        for (s in listOf(DONE, PENDING, FAILED)) assertEquals(s, WORKING.to(s))
        assertEquals(PENDING, DONE.to(PENDING))
        assertEquals(PENDING, FAILED.to(PENDING))
        for (s in InboxState.entries) assertEquals(s, s.to(s))
    }

    @Test
    fun `wild jumps throw`() {
        val bad = listOf(WAITING to WORKING, WAITING to DONE, PENDING to DONE, PENDING to WAITING, DONE to WORKING, FAILED to DONE, DONE to WAITING)
        for ((from, to) in bad) assertThrows("$from → $to", IllegalStateException::class.java) { from.to(to) }
    }

    private fun land(redacted: Boolean = false, last: Boolean = false, matched: Set<Long> = setOf(1), existing: Existing? = null) =
        Landing.of(redacted, last, matched, existing)

    @Test
    fun `new notices go in, redacted ones wait`() {
        assertEquals(Landing.Insert(PENDING, setOf(1)), land())
        assertEquals(Landing.Insert(WAITING, setOf(1)), land(redacted = true))
        assertEquals(Landing.Insert(PENDING, setOf(1)), land(redacted = true, last = true))
        assertEquals(Landing.Skip, land(matched = emptySet()))
    }

    @Test
    fun `the same notice again only adds rules it did not have`() {
        val pending = Existing(7, PENDING, setOf(1), sameText = true)
        assertEquals(Landing.Skip, land(existing = pending))
        assertEquals(Landing.AddRules(7, setOf(2), PENDING), land(matched = setOf(1, 2), existing = pending))
        assertEquals(Landing.AddRules(7, setOf(2), WORKING), land(matched = setOf(1, 2), existing = pending.copy(state = WORKING)))
        assertEquals(Landing.AddRules(7, setOf(2), PENDING), land(matched = setOf(2), existing = pending.copy(state = DONE)))
        assertEquals(Landing.AddRules(7, setOf(2), PENDING), land(matched = setOf(2), existing = pending.copy(state = FAILED)))
    }

    @Test
    fun `a waiting redacted one is released on the last look`() {
        val waiting = Existing(7, WAITING, setOf(1), sameText = true)
        assertEquals(Landing.Skip, land(redacted = true, existing = waiting))
        assertEquals(Landing.AddRules(7, setOf(2), WAITING), land(redacted = true, matched = setOf(1, 2), existing = waiting))
        assertEquals(Landing.AddRules(7, emptySet(), PENDING), land(redacted = true, last = true, existing = waiting))
    }

    @Test
    fun `the real text replaces the redacted one or deletes it`() {
        val twin = Existing(7, WAITING, setOf(1, 2), sameText = false)
        assertEquals(Landing.Replace(7, setOf(2)), land(matched = setOf(2), existing = twin))
        assertEquals(Landing.Delete(7), land(matched = emptySet(), existing = twin))
        assertEquals(Landing.Skip, land(redacted = true, existing = twin))
    }
}
