package com.abc.daodian.intake.legacy

import com.abc.daodian.intake.legacy.OldSetup.Person
import org.junit.Assert.assertEquals
import org.junit.Test

class OldSetupTest {

    @Test
    fun `people come back in the order they were added`() {
        val entries = setOf("200\u001F妈\u001F", "100\u001F老婆\u001F#", "坏的一串")
        assertEquals(listOf(Person("老婆", "#"), Person("妈", "")), OldSetup.people(entries, null, null))
    }

    @Test
    fun `the one-person version is read from who and code`() {
        assertEquals(listOf(Person("老婆", "#")), OldSetup.people(null, " 老婆 ", "#"))
        assertEquals(emptyList<Person>(), OldSetup.people(null, null, null))
        // 照新样子存过就不看旧键了
        assertEquals(emptyList<Person>(), OldSetup.people(emptySet(), "老婆", "#"))
    }

    @Test
    fun `the sentences put into the input box`() {
        assertEquals("帮我监听招商银行、支付宝的通知，记账用", OldSetup.ledgerSay(listOf("招商银行", "支付宝")))
        assertEquals(
            "帮我监听微信里老婆、妈发来的消息，要我做的事提醒我，老婆的要以「#」开头",
            OldSetup.relaySay(listOf("微信"), listOf(Person("老婆", "#"), Person("妈", "")))
        )
        assertEquals("帮我监听妈发来的消息，要我做的事提醒我", OldSetup.relaySay(emptyList(), listOf(Person("妈", ""))))
        assertEquals("老婆（暗号 #）、妈", OldSetup.peopleText(listOf(Person("老婆", "#"), Person("妈", ""))))
    }
}
