package com.abc.daodian.ledger.reconciliation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 读不出金额的通知哪几条还要问：弹通知、虚线段、开对账三处都照这个数 */
class LedgerCheckTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_792_000_000_000L

    @Test
    fun recentOnesAreAsked() {
        assertTrue(LedgerCheck.asks(now, now))
        assertTrue(LedgerCheck.asks(now - 2 * day, now))
        assertTrue(LedgerCheck.asks(now - 3 * day, now))
    }

    /** 以前就是这几条：开对账不拿，通知却每晚把它们数进去 */
    @Test
    fun olderThanThreeDaysAreNotAsked() {
        assertFalse(LedgerCheck.asks(now - 3 * day - 1, now))
        assertFalse(LedgerCheck.asks(now - 30 * day, now))
    }
}
