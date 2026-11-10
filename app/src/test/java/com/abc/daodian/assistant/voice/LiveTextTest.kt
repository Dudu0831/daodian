package com.abc.daodian.assistant.voice

import com.abc.daodian.assistant.voice.VoiceInput.Companion.join
import com.abc.daodian.assistant.voice.VoiceInput.Companion.soften
import com.abc.daodian.assistant.voice.VoiceInput.Companion.unsettled
import org.junit.Assert.assertEquals
import org.junit.Test

/** 边说边出的字怎么收拾标点（§8.3） */
class LiveTextTest {

    @Test
    fun `没锁的字不带句尾标点`() {
        assertEquals("明天下午三点", unsettled("明天下午三点。"))
        assertEquals("明天下午，三点", unsettled("明天下午，三点，"))
        assertEquals("remind me at 3:30", unsettled("remind me at 3:30. "))
        assertEquals("", unsettled("。"))
    }

    @Test
    fun `后面还有话，句号降成逗号，问号叹号留着`() {
        assertEquals("明天下午，", soften("明天下午。"))
        assertEquals("call mom,", soften("call mom."))
        assertEquals("几点了？", soften("几点了？"))
        assertEquals("好的", soften("好的"))
        assertEquals("", soften(""))
    }

    @Test
    fun `降成逗号的英文接下一截照样补空格`() {
        assertEquals("call mom, tomorrow", join(soften("call mom."), "tomorrow"))
        assertEquals("明天下午，三点开会", join(soften("明天下午。"), "三点开会"))
    }
}
