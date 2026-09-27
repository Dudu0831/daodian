package com.abc.daodian.agent.memory.tidy

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 喂给整理员的对话记录：人读的样子，查询结果不抄、问卡的答案要抄 */
class TidyTextTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = ZonedDateTime.parse("2026-09-24T21:03:00+08:00[Asia/Shanghai]")

    private val turn = Turn(
        5, listOf(
            Item.UserMessage("这个月吃饭花了多少，另外晚点提醒我交周报", at),
            Item.ToolCall("q", "list_expenses", "{\"category\":\"餐饮\"}"),
            Item.ToolResult("q", "#12 午饭 36.50\n#13 晚饭 58.00", ok = true),
            Item.ToolCall("a", "ask_user", "{}"),
            Item.ToolResult("a", "什么时候提醒？ 21:00\nanswer={\"0\":\"21:00\"}", ok = true),
            Item.AssistantMessage("吃饭花了 94.50。\n```svg\n<svg viewBox=\"0 0 1 1\"></svg>\n```"),
        )
    )

    @Test
    fun `transcript reads like a log`() {
        val text = TidyText.transcript(listOf(turn), setOf("list_expenses"), zone)
        assertTrue(text.startsWith("[9月24日 周四 21:03] 他：这个月吃饭花了多少"))
        assertTrue(text.contains("  · 成了：（查询结果，省略）"))
        assertFalse(text.contains("#12 午饭"))
        assertTrue(text.contains("  · 他答：什么时候提醒？ 21:00"))
        assertFalse(text.contains("answer="))
        assertFalse(text.contains("<svg"))
    }

    @Test
    fun `trigger turns are marked as not his words`() {
        val t = Turn(6, listOf(Item.UserMessage("每晚对账：有 3 笔没认出来", at, trigger = true), Item.AssistantMessage("好")))
        assertTrue(TidyText.transcript(listOf(t), emptySet(), zone).contains("（app 自动发起，不是他说的）每晚对账"))
    }

    @Test
    fun `input asks for a summary only when compacting`() {
        val digest = Digest("他让记了周五交周报。", 4, at.toInstant().toEpochMilli())
        val compact = TidyText.input(emptyList(), digest, listOf(turn), compact = true, stale = emptySet(), zone = zone)
        assertTrue(compact.contains("之前的摘要：（到 9月24日 为止）\n他让记了周五交周报。"))
        assertTrue(compact.endsWith("填进 summary。"))

        val plain = TidyText.input(emptyList(), digest, listOf(turn), compact = false, stale = emptySet(), zone = zone)
        assertFalse(plain.contains("之前的摘要"))
        assertTrue(plain.contains("还没有"))
        assertTrue(plain.endsWith("summary 填 null。"))

        val summaryOnly = TidyText.input(emptyList(), digest, listOf(turn), compact = true, stale = emptySet(), remember = false, zone = zone)
        assertTrue(summaryOnly.contains("add、update、remove 都给空数组"))
    }

    @Test
    fun `take stops at the limits but always takes one`() {
        val huge = Turn(1, listOf(Item.UserMessage("字".repeat(TidyText.MAX_CHARS + 10), at)))
        assertEquals(1, TidyText.take(listOf(huge, turn), emptySet()).size)
        val many = (1L..(TidyText.MAX_TURNS + 5L)).map { Turn(it, listOf(Item.UserMessage("嗯", at))) }
        assertEquals(TidyText.MAX_TURNS, TidyText.take(many, emptySet()).size)
    }
}
