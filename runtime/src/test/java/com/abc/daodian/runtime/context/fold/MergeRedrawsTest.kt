package com.abc.daodian.runtime.context.fold

import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画坏了、重画过的两轮，喂给模型时并成一轮 */
class MergeRedrawsTest {

    private val now = ZonedDateTime.parse("2026-11-13T15:00:00+08:00[Asia/Shanghai]")
    private val bad = "这个月：\n```html\n<script>oops(</script>\n```"
    private val good = "这个月：\n```html\n<p>好了</p>\n```"

    private fun asked(id: Long) = Turn(
        id,
        listOf(
            Item.UserMessage("画一下这个月的开销", now),
            Item.ToolCall("c1", "list_expenses", "{}"),
            Item.ToolResult("c1", "餐饮 1,234.50", ok = true),
            Item.AssistantMessage(bad)
        )
    )

    private fun redraw(id: Long, reply: String? = good) = Turn(
        id,
        listOfNotNull(Item.UserMessage(MergeRedraws.request("Unexpected token"), now, trigger = true), reply?.let { Item.AssistantMessage(it) })
    )

    private fun plain(id: Long) = Turn(id, listOf(Item.UserMessage("好", now), Item.AssistantMessage("嗯")))

    @Test
    fun `once the talk has moved on the broken turn and its redraw read as one`() {
        val out = MergeRedraws.merge(listOf(plain(1), asked(2), redraw(3), plain(4)))
        assertEquals(listOf(1L, 3L, 4L), out.map { it.id })
        val merged = out[1]
        // 他的原话、当时查到的数、重画出来的那张；画坏的那张和「没画出来」那句都不在了
        assertEquals("画一下这个月的开销", merged.input.text)
        assertEquals(listOf("c1", "c1"), merged.items.mapNotNull { (it as? Item.ToolCall)?.callId ?: (it as? Item.ToolResult)?.callId })
        assertEquals(listOf(good), merged.items.filterIsInstance<Item.AssistantMessage>().map { it.text })
        assertTrue(merged.items.none { it is Item.UserMessage && it.trigger })
    }

    @Test
    fun `the redraw still running sees the error it was told`() {
        val turns = listOf(plain(1), asked(2), redraw(3))
        assertSame(turns, MergeRedraws.merge(turns).takeIf { it == turns }?.let { turns })
        // 重画那轮什么都没说出来（被停了）：不并
        val stopped = listOf(asked(1), redraw(2, reply = null), plain(3))
        assertEquals(listOf(1L, 2L, 3L), MergeRedraws.merge(stopped).map { it.id })
    }

    @Test
    fun `an echoed fold line is dropped like a broken drawing`() {
        val echoed = Turn(1, listOf(Item.UserMessage("做个俄罗斯方块", now), Item.AssistantMessage("下面这个可以玩：\n${FoldDrawings.FOLDED}")))
        val out = MergeRedraws.merge(listOf(echoed, redraw(2), plain(3)))
        assertEquals(listOf(good), out[0].items.filterIsInstance<Item.AssistantMessage>().map { it.text })
    }
}
