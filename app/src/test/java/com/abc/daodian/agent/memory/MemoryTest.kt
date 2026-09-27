package com.abc.daodian.agent.memory

import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceState
import com.abc.daodian.agent.memory.tidy.Digest
import com.abc.daodian.agent.memory.tidy.SaveTidyTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 记忆的规矩、对话里的 edit_memory、后台交作业的 save_tidy、垫在前面的那段、痕 */
class MemoryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.parse("2026-09-24T21:00:00+08:00[Asia/Shanghai]")
    private val sept20 = ZonedDateTime.parse("2026-09-20T10:00:00+08:00[Asia/Shanghai]").toInstant().toEpochMilli()

    private val ctx = ToolContext(now, "")

    @Test
    fun `rules catch what should not go in`() {
        val current = listOf(Memory(3, "他周二健身", 0, Memory.TIDY))
        assertNull(MemoryRules.problemOf(MemoryEdit(add = listOf("他说的「晚点」一般指晚上 9 点")), current))
        assertTrue(MemoryRules.problemOf(MemoryEdit(add = listOf("字".repeat(61))), current)!!.contains("太长"))
        assertTrue(MemoryRules.problemOf(MemoryEdit(add = listOf("他的卡号 6222021234567890123")), current)!!.contains("卡号"))
        assertTrue(MemoryRules.problemOf(MemoryEdit(add = listOf("家里 wifi 密码 abc")), current)!!.contains("密码"))
        assertTrue(MemoryRules.problemOf(MemoryEdit(remove = setOf(9)), current)!!.contains("m9"))
        val full = (1L..MemoryRules.MAX_COUNT).map { Memory(it, "第 $it 条", 0, Memory.TIDY) }
        assertTrue(MemoryRules.problemOf(MemoryEdit(add = listOf("再一条")), full)!!.contains("记满了"))
        assertNull(MemoryRules.problemOf(MemoryEdit(add = listOf("再一条"), remove = setOf(1)), full))
    }

    @Test
    fun `edit_memory writes and reports line by line`() = runBlocking {
        val book = FakeMemoryBook("他周二健身", "他在备考 CPA")
        val out = EditMemoryTool(book).execute(
            """{"add":["他说的「晚点」一般指晚上 9 点"],"update":[{"id":1,"text":"他周二、周四晚上健身"}],"remove":[2]}""", ctx
        )
        assertTrue(out.ok)
        assertEquals(listOf("他周二、周四晚上健身", "他说的「晚点」一般指晚上 9 点"), book.list.map { it.text })
        assertEquals(listOf("记好了。", "+ m3 他说的「晚点」一般指晚上 9 点", "~ m1 他周二、周四晚上健身", "- m2 他在备考 CPA"), out.output.lines())
        assertEquals(Memory.SAID, book.list.last().source)
    }

    @Test
    fun `edit_memory refuses bad edits without touching the book`() = runBlocking {
        val book = FakeMemoryBook("他周二健身")
        assertFalse(EditMemoryTool(book).execute("""{"add":[],"update":[],"remove":[]}""", ctx).ok)
        val bad = EditMemoryTool(book).execute("""{"add":[],"update":[],"remove":[7]}""", ctx)
        assertFalse(bad.ok)
        assertTrue(bad.output.startsWith("没记。"))
        assertEquals(1, book.list.size)
    }

    @Test
    fun `save_tidy needs a summary only when compacting`() = runBlocking {
        val book = FakeMemoryBook()
        val none = """{"add":[],"update":[],"remove":[],"summary":null}"""
        assertTrue(SaveTidyTool(book, compact = false).execute(none, ctx).ok)
        assertFalse(SaveTidyTool(book, compact = true).execute(none, ctx).ok)

        val out = SaveTidyTool(book, compact = true).execute("""{"add":["他住在杭州"],"update":[],"remove":[],"summary":"他让记了周五交周报。"}""", ctx)
        val saved = out.payload as SaveTidyTool.Saved
        assertEquals("他让记了周五交周报。", saved.summary)
        assertEquals(listOf("他住在杭州"), book.list.map { it.text })
        assertEquals(Memory.TIDY, book.list.single().source)
    }

    @Test
    fun `with auto tidy off only the summary is taken`() = runBlocking {
        val book = FakeMemoryBook("每周二健身")
        val out = SaveTidyTool(book, compact = true, remember = false)
            .execute("""{"add":["住在杭州"],"update":[],"remove":[1],"summary":"他让记了周五交周报。"}""", ctx)
        assertTrue(out.ok)
        assertEquals("他让记了周五交周报。", (out.payload as SaveTidyTool.Saved).summary)
        assertEquals(listOf("每周二健身"), book.list.map { it.text })
    }

    @Test
    fun `preamble lists memories with ids and the digest`() {
        assertNull(Recall.preamble(emptyList(), null, zone))
        val p = Recall.preamble(
            listOf(Memory(3, "他说的「晚点」一般指晚上 9 点", sept20, Memory.SAID)),
            Digest("他让记了周五交周报。", through = 12, throughAt = sept20),
            zone
        )!!
        assertEquals(12, p.afterTurnId)
        assertTrue(p.text.contains("- m3 · 9月20日 · 他说的「晚点」一般指晚上 9 点"))
        assertTrue(p.text.contains("到 9月20日 为止"))
        assertTrue(p.text.endsWith("他让记了周五交周报。"))
    }

    @Test
    fun `trace says what was remembered`() {
        fun trace(state: TraceState, output: String?, args: String = "{}") =
            MemoryTrace.of(ToolTrace(EditMemoryTool.NAME, args, state, output, null))!!

        val one = trace(TraceState.OK, "记好了。\n+ m3 他说的「晚点」一般指晚上 9 点")
        assertEquals("记住了", one.settled)
        assertEquals("他说的「晚点」一般指晚上 9 点", one.text)
        assertTrue(one.lines.isEmpty())

        val gone = trace(TraceState.OK, "记好了。\n- m2 他在备考 CPA")
        assertEquals("忘了", gone.settled)

        val many = trace(TraceState.OK, "记好了。\n+ m3 甲\n~ m1 乙")
        assertEquals("改了记忆", many.settled)
        assertEquals(listOf("记住 · 甲", "改成 · 乙"), many.lines)

        assertEquals("太长", trace(TraceState.FAILED, "没记。太长（78 字），一条 60 字以内。").text.take(2))
        assertEquals("他住杭", trace(TraceState.RUNNING, null, """{"add":["他住杭""").text)
        assertEquals("", trace(TraceState.RUNNING, null, """{"add":[],"update":[{"id""").text)
        assertNull(MemoryTrace.of(ToolTrace("create_reminder", "{}", TraceState.OK, "", null)))
    }
}
