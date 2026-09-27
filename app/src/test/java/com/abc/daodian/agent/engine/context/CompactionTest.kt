package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** 攒够了成段地压、压完留最近几轮；旧的查询结果过几轮折成一句 */
class CompactionTest {

    private val now = ZonedDateTime.parse("2026-09-24T21:00:00+08:00[Asia/Shanghai]")

    private fun turns(ids: LongRange, reply: String = "嗯") =
        ids.map { Turn(it, listOf(Item.UserMessage("第 $it 句", now), Item.AssistantMessage(reply))) }

    @Test
    fun `nothing to compact until the window fills up`() {
        assertNull(Compaction.cut(turns(1L..Compaction.KEEP.toLong())))
        assertNull(Compaction.cut(turns(1L until Compaction.AT_TURNS.toLong())))
    }

    @Test
    fun `a full window compacts down to the last few turns`() {
        val ts = turns(11L until 11L + Compaction.AT_TURNS)
        val cut = Compaction.cut(ts)!!
        assertEquals(Compaction.KEEP, ts.count { it.id > cut })
    }

    @Test
    fun `a few huge turns also trigger it`() {
        val big = "字".repeat(Compaction.AT_CHARS / 7)
        val cut = Compaction.cut(turns(1L..8L, big))
        assertEquals(2L, cut)
    }

    @Test
    fun `stale query results fold outside the last few turns, the call stays`() {
        val table = "#12 午饭 36.50\n".repeat(40)
        val ts = (1L..5L).map { id ->
            Turn(id, listOf(
                Item.UserMessage("查一下", now),
                Item.ToolCall("c$id", "list_expenses", "{}"),
                Item.ToolResult("c$id", table, ok = true),
                Item.ToolCall("s$id", "fake_write", "{}"),
                Item.ToolResult("s$id", "好".repeat(300), ok = true),
                Item.AssistantMessage("吃饭花了 36.50")
            ))
        }
        val out = FoldStale(setOf("list_expenses"), LastTurns(10)).select(ts)

        val folded = out.take(5 - FoldStale.KEEP)
        folded.forEach { t ->
            assertTrue(t.items.any { it is Item.ToolCall && it.name == "list_expenses" })
            assertEquals(FoldStale.FOLDED, (t.items[2] as Item.ToolResult).output)
            assertEquals("好".repeat(300), (t.items[4] as Item.ToolResult).output)   // 不在名单里的不动
        }
        out.takeLast(FoldStale.KEEP).forEachIndexed { i, t -> assertSame(ts[5 - FoldStale.KEEP + i], t) }
    }

    @Test
    fun `short stale results are not worth folding`() {
        val t = Turn(1, listOf(Item.UserMessage("查", now), Item.ToolCall("c", "list_expenses", "{}"), Item.ToolResult("c", "没有", ok = true)))
        val out = FoldStale(setOf("list_expenses"), LastTurns(10), keep = 1).select(listOf(t, turns(2L..2L).single()))
        assertSame(t, out.first())
    }
}
