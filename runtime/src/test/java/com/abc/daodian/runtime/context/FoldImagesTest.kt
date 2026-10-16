package com.abc.daodian.runtime.context

import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 他早先发来的图片折成一句话再喂给模型：最近三轮原样带，没等到回应的那轮不管新旧都折 */
class FoldImagesTest {

    private val now = ZonedDateTime.parse("2026-10-12T21:00:00+08:00[Asia/Shanghai]")
    private val two = listOf("/img/1_576x1280.jpg", "/img/2_960x1280.jpg")
    private val one = two.take(1)

    private fun turn(id: Long, text: String, images: List<String> = emptyList(), answered: Boolean = true) =
        Turn(id, listOfNotNull(Item.UserMessage(text, now, images = images), Item.AssistantMessage("好").takeIf { answered }))

    @Test
    fun `pictures older than the last three turns fold into a note`() {
        val turns = listOf(turn(1, "按这个提醒我", two), turn(2, "", one), turn(3, "嗯"), turn(4, "这张呢", one), turn(5, "好"))
        val out = FoldImages(LastTurns(10)).select(turns)

        assertTrue(out[0].input.images.isEmpty())
        assertEquals("按这个提醒我\n${FoldImages.folded(2)}", out[0].input.text)
        // 只发了图的那句，折完只剩那句交代
        assertEquals(FoldImages.folded(1), out[1].input.text)
        // 回应原样留着
        assertEquals("好", (out[0].items[1] as Item.AssistantMessage).text)
        // 最近三轮不动
        (2..4).forEach { assertSame(turns[it], out[it]) }
        assertEquals(one, out[3].input.images)
    }

    @Test
    fun `a turn that never got a reply loses its pictures even when it is recent`() {
        // 模型看不了图：带图的那句被退回来了，他没重试、接着说别的 —— 下一句不能还带着那张图
        val turns = listOf(turn(1, "看这张", one, answered = false), turn(2, "算了，明早八点叫我"))
        val out = FoldImages(LastTurns(10)).select(turns)
        assertTrue(out[0].input.images.isEmpty())
        assertTrue(FoldImages.MARK in out[0].input.text)

        // 当前这一轮还没等到回应是正常的，图得带着
        val current = listOf(turn(1, "好"), turn(2, "看这张", one, answered = false))
        assertSame(current[1], FoldImages(LastTurns(10)).select(current)[1])
    }

    @Test
    fun `turns without pictures pass through as they are`() {
        val turns = (1L..6L).map { turn(it, "第 $it 句") }
        val out = FoldImages(LastTurns(10)).select(turns)
        turns.indices.forEach { assertSame(turns[it], out[it]) }
    }
}
