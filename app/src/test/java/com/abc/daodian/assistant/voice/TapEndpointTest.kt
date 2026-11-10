package com.abc.daodian.assistant.voice

import com.abc.daodian.assistant.voice.VoiceInput.Companion.RATE
import com.abc.daodian.assistant.voice.VoiceInput.Companion.tapEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 速记点印才收，只兜两种忘了收的（§8.3） */
class TapEndpointTest {

    private fun s(seconds: Double) = (seconds * RATE).toLong()

    @Test
    fun `一直没开口，8 秒内不收，过了收`() {
        assertNull(tapEndpoint(spoke = false, fed = s(7.9), lastSpeechAt = 0, speaking = false))
        assertEquals("silent start", tapEndpoint(spoke = false, fed = s(8.1), lastSpeechAt = 0, speaking = false))
    }

    @Test
    fun `说过话之后停几秒不收`() {
        assertNull(tapEndpoint(spoke = true, fed = s(6.0), lastSpeechAt = s(3.0), speaking = false))
        assertNull(tapEndpoint(spoke = true, fed = s(12.9), lastSpeechAt = s(3.0), speaking = false))
    }

    @Test
    fun `说过话之后连着静 10 秒，当说完了`() {
        assertEquals("long silence", tapEndpoint(spoke = true, fed = s(13.1), lastSpeechAt = s(3.0), speaking = false))
    }

    @Test
    fun `正在说就不收`() {
        assertNull(tapEndpoint(spoke = true, fed = s(40.0), lastSpeechAt = s(3.0), speaking = true))
    }

    @Test
    fun `开口晚也行：7 秒才开口，只看开口之后的停顿`() {
        assertNull(tapEndpoint(spoke = true, fed = s(15.0), lastSpeechAt = s(7.5), speaking = false))
    }
}
