package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 旧轮次里的图折成一句话再喂给模型，当前这一轮不动 */
class FoldDrawingsTest {

    private val now = ZonedDateTime.parse("2026-09-24T21:00:00+08:00[Asia/Shanghai]")
    private val chart = "<svg viewBox=\"0 0 340 200\">\n  <rect width=\"10\" height=\"10\"/>\n</svg>"

    private fun turn(id: Long, reply: String) = Turn(id, listOf(Item.UserMessage("这个月花在哪", now), Item.AssistantMessage(reply)))

    private fun Turn.reply() = (items[1] as Item.AssistantMessage).text

    @Test
    fun `fenced and bare drawings fold to one line`() {
        assertEquals("看图：\n${FoldDrawings.FOLDED}\n餐饮最多。", FoldDrawings.fold("看图：\n```svg\n$chart\n```\n餐饮最多。"))
        assertEquals("看图：\n${FoldDrawings.FOLDED}\n完", FoldDrawings.fold("看图：\n```\n<?xml version=\"1.0\"?>\n$chart\n```\n完"))
        assertEquals("看 ${FoldDrawings.FOLDED} 完", FoldDrawings.fold("看 $chart 完"))
        // 没写完的图不动
        val half = "```svg\n<svg viewBox=\"0 0 1 1\">\n<rect/>"
        assertEquals(half, FoldDrawings.fold(half))
    }

    @Test
    fun `only earlier turns are folded`() {
        val drawn = "```svg\n$chart\n```"
        val picked = FoldDrawings(LastTurns(3)).select(listOf(turn(1, drawn), turn(2, "没有图"), turn(3, drawn), turn(4, drawn)))
        assertEquals(listOf(2L, 3L, 4L), picked.map { it.id })
        assertEquals("没有图", picked[0].reply())
        assertEquals(FoldDrawings.FOLDED, picked[1].reply())
        assertEquals(drawn, picked[2].reply())
    }

    @Test
    fun `turns without drawings are passed through as they are`() {
        val plain = turn(1, "餐饮 1,234.50")
        val picked = FoldDrawings().select(listOf(plain, turn(2, "好")))
        assertSame(plain, picked[0])
        assertTrue(picked.size == 2)
    }
}
