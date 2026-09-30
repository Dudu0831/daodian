package com.abc.daodian.intake

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private class Fake(override val id: String) : NoticeSubscriber {
        override val label = id
        override val purpose = ""
        override suspend fun accept(context: Context, notice: Notice) = Unit
    }

    private val ledger = Fake("ledger")
    private val relay = Fake("relay")
    private val router = Router(listOf(ledger, relay))

    @Test
    fun `only subscribers that picked the app get it`() {
        val routes = mapOf("ledger" to setOf("cmb", "com.tencent.mm"), "relay" to setOf("com.tencent.mm"))
        assertEquals(listOf(ledger), router.targets("cmb", routes))
        assertEquals(listOf(ledger, relay), router.targets("com.tencent.mm", routes))
    }

    @Test
    fun `nobody picked it means nobody gets it`() {
        assertTrue(router.targets("com.taobao", mapOf("ledger" to setOf("cmb"))).isEmpty())
        assertTrue(router.targets("cmb", emptyMap()).isEmpty())
    }

    @Test
    fun `ids in the table that no code registered are ignored`() {
        val routes = mapOf("gone" to setOf("cmb"), "relay" to setOf("cmb"))
        assertEquals(listOf(relay), router.targets("cmb", routes))
    }

    @Test
    fun `one subscriber throwing does not stop the other`() = runBlocking {
        val got = mutableListOf<String>()
        val done = router.deliver(listOf(ledger, relay)) { s ->
            if (s === ledger) error("库坏了")
            got += s.id
        }
        assertEquals(listOf("relay"), got)
        assertEquals(listOf(relay), done)
    }

    @Test
    fun `a slow subscriber does not hold up the other`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        router.deliver(listOf(ledger, relay)) { s ->
            if (s === ledger) gate.await() else gate.complete(Unit)
            synchronized(order) { order += s.id }
        }
        // 记账等着派活先收完才放行：同时交的话不会卡死，派活先到
        assertEquals(listOf("relay", "ledger"), order)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate ids are refused`() {
        Router(listOf(Fake("ledger"), Fake("ledger")))
    }
}
