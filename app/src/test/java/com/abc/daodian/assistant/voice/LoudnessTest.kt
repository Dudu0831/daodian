package com.abc.daodian.assistant.voice

import com.abc.daodian.assistant.voice.VoiceInput.Companion.GATE_DB
import com.abc.daodian.assistant.voice.VoiceInput.Companion.RATE
import com.abc.daodian.assistant.voice.VoiceInput.Companion.loudness
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 近讲门拿来比的「这一截有多响」（§8.3） */
class LoudnessTest {

    /** [seconds] 秒、振幅 [amp] 的 200Hz 正弦 */
    private fun tone(seconds: Double, amp: Float) =
        FloatArray((seconds * RATE).toInt()) { (amp * sin(2 * PI * 200 * it / RATE)).toFloat() }

    private fun db(a: FloatArray) = loudness(a, 0, a.size)

    @Test
    fun `振幅差十倍就是差 20dB`() {
        assertEquals(20f, db(tone(1.0, 0.3f)) - db(tone(1.0, 0.03f)), 0.5f)
    }

    @Test
    fun `夹着停顿不拉低`() {
        val steady = tone(2.0, 0.3f)
        val paused = tone(1.0, 0.3f) + FloatArray(RATE / 2) + tone(1.0, 0.3f)
        assertEquals(db(steady), db(paused), 0.5f)
    }

    @Test
    fun `一下特别响的不抬高`() {
        val speech = tone(2.0, 0.05f)
        val bumped = speech.copyOf().also { a -> for (i in 0 until RATE / 20) a[RATE + i] = 0.9f }
        assertEquals(db(speech), db(bumped), 0.5f)
    }

    @Test
    fun `一两米外的人声比凑近说的轻过门槛`() {
        // 15 厘米和 1.5 米，声压差十倍
        assertTrue(db(tone(1.0, 0.2f)) - db(tone(1.0, 0.02f)) > GATE_DB)
    }

    @Test
    fun `太短算不出来，不崩`() {
        assertEquals(-100f, loudness(FloatArray(100), 0, 100), 0f)
    }
}
