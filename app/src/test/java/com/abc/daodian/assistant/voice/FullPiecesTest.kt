package com.abc.daodian.assistant.voice

import com.abc.daodian.assistant.voice.VoiceInput.Companion.FULL_PAD
import com.abc.daodian.assistant.voice.VoiceInput.Companion.RATE
import com.abc.daodian.assistant.voice.VoiceInput.Companion.fullPieces
import org.junit.Assert.assertEquals
import org.junit.Test

/** 收尾整句重认怎么分块（§8.3） */
class FullPiecesTest {

    private fun s(seconds: Double) = (seconds * RATE).toLong()

    @Test
    fun `没说话就不认`() {
        assertEquals(emptyList<Pair<Long, Long>>(), fullPieces(emptyList(), s(8.0)))
    }

    @Test
    fun `短的一整块，前后各多带一截，不出录音的头尾`() {
        val segs = listOf(s(0.1) to s(1.5), s(1.9) to s(4.0), s(4.4) to s(6.0))
        assertEquals(listOf(0L to s(6.0) + FULL_PAD), fullPieces(segs, s(9.0)))
        assertEquals(listOf(0L to s(6.1)), fullPieces(segs, s(6.1)))
    }

    @Test
    fun `长的在两段之间切开，块不超过 20 秒加两头`() {
        val segs = listOf(
            s(1.0) to s(8.0), s(9.0) to s(16.0), s(17.0) to s(20.5),
            s(21.5) to s(29.0), s(30.0) to s(37.0)
        )
        val pieces = fullPieces(segs, s(40.0))
        assertEquals(
            listOf(s(1.0) - FULL_PAD to s(20.5) + FULL_PAD, s(21.5) - FULL_PAD to s(37.0) + FULL_PAD),
            pieces
        )
    }

    @Test
    fun `停顿比两头多带的短，在停顿正中切，不重叠`() {
        val segs = listOf(s(0.5) to s(8.5), s(8.6) to s(16.0), s(16.2) to s(24.0))
        assertEquals(
            listOf(s(0.5) - FULL_PAD to s(16.1), s(16.1) to s(24.0) + FULL_PAD),
            fullPieces(segs, s(25.0))
        )
    }

    @Test
    fun `挑停得最久的空隙切，不在一句话中间的短停顿切`() {
        val segs = listOf(
            s(0.5) to s(6.0), s(6.6) to s(9.0), s(9.6) to s(12.0),
            // 12 到 14 停了 2 秒：句子之间
            s(14.0) to s(18.0), s(18.6) to s(20.0), s(20.6) to s(26.0)
        )
        assertEquals(
            listOf(s(0.5) - FULL_PAD to s(12.0) + FULL_PAD, s(14.0) - FULL_PAD to s(26.0) + FULL_PAD),
            fullPieces(segs, s(27.0))
        )
    }

    @Test
    fun `开头的长停顿不切出一小块`() {
        val segs = listOf(s(0.5) to s(2.0), s(5.0) to s(12.0), s(12.6) to s(19.0), s(19.6) to s(26.0))
        assertEquals(s(19.0) + FULL_PAD, fullPieces(segs, s(27.0)).first().second)
    }
}
