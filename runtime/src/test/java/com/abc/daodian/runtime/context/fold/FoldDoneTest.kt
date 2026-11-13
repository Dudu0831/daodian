package com.abc.daodian.runtime.context.fold

import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更早几轮里办完的事只留头一行 */
class FoldDoneTest {

    private val now = ZonedDateTime.parse("2026-11-13T15:00:00+08:00[Asia/Shanghai]")
    private val long = "已建提醒 #12 · 周五 09:00\n" + "细目 ".repeat(100)

    private fun turn(id: Long, tool: String, output: String) = Turn(
        id,
        listOf(Item.UserMessage("第 $id 句", now), Item.ToolCall("c$id", tool, "{}"), Item.ToolResult("c$id", output, ok = true), Item.AssistantMessage("好"))
    )

    private fun Turn.output() = (items[2] as Item.ToolResult).output

    @Test
    fun `old long results keep their first line and recent ones stay whole`() {
        val turns = (1L..8L).map { turn(it, "create_reminder", long) }
        val out = FoldDone(emptySet(), LastTurns(20)).select(turns)
        assertEquals("已建提醒 #12 · 周五 09:00" + FoldDone.TAIL, out[0].output())
        assertEquals(long, out[2].output())
        assertEquals(2, out.count { it.output().endsWith(FoldDone.TAIL) })
    }

    @Test
    fun `short results and query results are left alone`() {
        val short = turn(1, "create_reminder", "已建提醒 #12")
        val query = turn(2, "list_expenses", long)
        val out = FoldDone(setOf("list_expenses"), LastTurns(20), keep = 1).select(listOf(short, query, turn(3, "x", "y")))
        assertSame(short, out[0])
        assertSame(query, out[1])
    }

    @Test
    fun `an answered ask loses only its machine line`() {
        val asked = turn(1, AskUserTool.NAME, "用户选了：晚上 9 点\nanswer=[0]")
        val out = FoldDone(emptySet(), LastTurns(20), keep = 1).select(listOf(asked, turn(2, "x", "y")))
        assertEquals("用户选了：晚上 9 点", out[0].output())
        assertTrue(out[0].items[1] is Item.ToolCall)
    }
}
