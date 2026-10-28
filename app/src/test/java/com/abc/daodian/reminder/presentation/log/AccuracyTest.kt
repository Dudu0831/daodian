package com.abc.daodian.reminder.presentation.log

import com.abc.daodian.reminder.data.FireLog
import com.abc.daodian.reminder.data.FireSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「响得准不准」那一页的统计：只看最近 30 次、结论怎么定 */
class AccuracyTest {

    /** 新的在前，和 `observeRecent` 一样 */
    private fun logs(vararg drifts: Long, source: (Int) -> FireSource = { FireSource.ALARM }) =
        drifts.mapIndexed { i, d -> FireLog(id = i + 1L, reminderId = 1, title = "t", scheduledAt = 1_000_000L - i * 1000, firedAt = 1_000_000L - i * 1000 + d, source = source(i)) }

    @Test
    fun `nothing yet`() {
        assertEquals(Accuracy.Verdict.NONE, Accuracy.of(emptyList()).verdict)
    }

    @Test
    fun `median, slowest and only the last 30`() {
        val a = Accuracy.of(logs(*LongArray(40) { if (it == 35) 90_000 else 400L + it }))
        assertEquals(30, a.count)
        assertEquals(415L, a.medianMillis)
        assertEquals(429L, a.maxMillis)
        assertEquals(Accuracy.Verdict.GOOD, a.verdict)
        // 从旧到新：最后一根是最新那次
        assertEquals(1L, a.recent.last().id)
    }

    @Test
    fun `slow beyond 30 seconds, fallback beats slow`() {
        assertEquals(Accuracy.Verdict.SLOW, Accuracy.of(logs(400, 31_000, 500)).verdict)
        val squeezed = Accuracy.of(logs(400, 31_000, 500) { if (it == 2) FireSource.SWEEP else FireSource.ALARM })
        assertEquals(Accuracy.Verdict.SQUEEZED, squeezed.verdict)
        assertEquals(1, squeezed.fallbacks)
    }

    @Test
    fun `drift reads with a sign`() {
        assertEquals("+0.4s", drift(400))
        assertEquals("−2.1s", drift(-2_100))
        assertEquals("+45s", drift(45_000))
    }
}
