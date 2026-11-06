package com.abc.daodian.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStatsTest {

    @Test
    fun `一次按住说话只记时长、解码次数和结果`() {
        val line = VoiceStats.sessionLine(0, hold = true, heardMs = 7_240, decodes = 18, avgMs = 121, maxMs = 240, outcome = "出字")
        assertTrue(line.endsWith("· 按住 · 7.2 秒 · 解 18 次 平均 121ms 最慢 240ms · 出字"))
    }

    @Test
    fun `没解过的写没解`() {
        val line = VoiceStats.sessionLine(0, hold = false, heardMs = 300, decodes = 0, avgMs = 0, maxMs = 0, outcome = "取消")
        assertTrue(line.endsWith("· 速记 · 0.3 秒 · 没解 · 取消"))
    }

    @Test
    fun `那一段有模型、加载状态、语音条和最近几行`() {
        val text = VoiceStats.describe(
            modelPresent = true,
            loadedNow = 1,
            voiceNotes = 3,
            voiceBytes = 150 * 1024,
            recent = listOf("a", "b")
        )
        assertEquals(
            """
            模型 ${VoiceStats.MODEL_NAME} · 在包里
            现在 加载着
            语音条 3 条 · 150 KB
            最近（新的在上）
              a
              b
            """.trimIndent(),
            text.trimEnd()
        )
    }

    @Test
    fun `缺模型、没用过`() {
        val text = VoiceStats.describe(modelPresent = false, loadedNow = 0, voiceNotes = 0, voiceBytes = 0, recent = emptyList())
        assertTrue("缺" in text)
        assertTrue("现在 没加载" in text)
        assertTrue("最近 没用过" in text)
        assertFalse("加载着" in text)
    }
}
