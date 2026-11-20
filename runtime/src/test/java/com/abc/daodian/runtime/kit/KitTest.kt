package com.abc.daodian.runtime.kit

import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.context.fold.FoldKits
import com.abc.daodian.runtime.engine.AgentLoop
import com.abc.daodian.runtime.engine.FakeWriteTool
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Session
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.model.Effort
import com.abc.daodian.runtime.model.LlmClient
import com.abc.daodian.runtime.model.LlmEvent
import com.abc.daodian.runtime.model.LlmException
import com.abc.daodian.runtime.model.LlmRequest
import com.abc.daodian.runtime.model.StepOutput
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.abc.daodian.runtime.tool.ToolRegistry
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具包：开了才带出来、过几轮收起、没开就调被挡。模型是照剧本念的假货（DESIGN §6.11） */
class KitTest {

    private val now = ZonedDateTime.parse("2026-11-14T10:00:00+08:00[Asia/Shanghai]")

    private class ScriptedLlm(private vararg val steps: StepOutput) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override fun step(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            emit(LlmEvent.Done(steps.getOrNull(requests.size - 1) ?: throw LlmException("剧本念完了")))
        }
        fun offered(i: Int) = requests[i].tools.map { it.name }
    }

    private fun tool(name: String, ran: MutableList<String>) = object : Tool {
        override val name = name
        override val description = "测试用"
        override val parameters: Map<String, Any?> = mapOf("type" to "object", "properties" to emptyMap<String, Any?>())
        override val effect = ToolEffect.READ
        override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
            ran += name
            return ToolOutcome("$name 办了", ok = true)
        }
    }

    private val ran = mutableListOf<String>()
    private val guide = "先 list_apps 查包名，再 add_rule。" + "规矩".repeat(150)
    private val notice = Kit("notice", "他想让你盯着手机通知时开", guide, listOf(tool("list_apps", ran), tool("add_rule", ran)))
    private val draw = Kit("draw", "他想看图时开", "写一个 html 代码块。", effort = Effort.HIGH)
    private val kits = listOf(notice, draw)

    /** 和 `AgentRuntime.loopOf` 拼的一样 */
    private fun loop(llm: LlmClient, keep: Int = KitGate.KEEP) = AgentLoop(
        llm, ToolRegistry(listOf<Tool>(FakeWriteTool { 1L }, OpenKitTool(kits)) + kits.flatMap { it.tools }),
        "sys", FoldKits(LastTurns(24), keep), selector = KitGate(kits, keep)
    )

    private fun open(id: String, kit: String) = Item.ToolCall(id, OpenKitTool.NAME, """{"name":"$kit"}""")
    private fun calls(vararg c: Item.ToolCall) = StepOutput("", c.toList(), "")
    private fun answer(text: String) = StepOutput(text, emptyList(), "")
    private fun results(session: Session) = session.turns.flatMap { it.items }.filterIsInstance<Item.ToolResult>()

    @Test
    fun `the tools of a kit show up only after it is opened, together with its guide`() = runBlocking {
        val llm = ScriptedLlm(calls(open("c1", "notice")), calls(Item.ToolCall("c2", "list_apps", "{}")), answer("好了"))
        val session = Session()

        loop(llm).run(session, "快递到了提醒我取", now).toList()

        assertEquals(listOf(FakeWriteTool.NAME, OpenKitTool.NAME), llm.offered(0))
        assertEquals(listOf(FakeWriteTool.NAME, OpenKitTool.NAME, "list_apps", "add_rule"), llm.offered(1))
        val opened = results(session).first()
        assertTrue(opened.ok)
        assertTrue(opened.output.contains("list_apps、add_rule 现在可以用了") && opened.output.endsWith(guide))
        assertEquals(listOf("list_apps"), ran)
    }

    @Test
    fun `calling a tool of a closed kit is refused with the way out, not executed`() = runBlocking {
        val llm = ScriptedLlm(calls(Item.ToolCall("c1", "add_rule", "{}")), answer("我先打开"))
        val session = Session()

        loop(llm).run(session, "建一条规则", now).toList()

        val refused = results(session).single()
        assertFalse(refused.ok)
        assertTrue(refused.output, refused.output.contains("open_kit") && refused.output.contains("「notice」"))
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a kit stays open for the next turns and closes when the opening falls out of the kept ones`() = runBlocking {
        val llm = ScriptedLlm(calls(open("c1", "notice")), answer("开了"), answer("二"), answer("三"))
        val session = Session()
        val loop = loop(llm, keep = 2)

        loop.run(session, "听通知", now).toList()
        loop.run(session, "接着说", now).toList()
        loop.run(session, "再说一句", now).toList()

        // 第二轮：打开的那轮还在最近 2 轮里，工具和说明都在
        assertTrue("add_rule" in llm.offered(2))
        assertTrue((llm.requests[2].input.filterIsInstance<Item.ToolResult>().single()).output.endsWith(guide))
        // 第三轮：收起来了，说明折成一句，工具撤掉；存着的那份没变
        assertEquals(listOf(FakeWriteTool.NAME, OpenKitTool.NAME), llm.offered(3))
        assertEquals(FoldKits.folded("notice"), llm.requests[3].input.filterIsInstance<Item.ToolResult>().single().output)
        assertTrue(results(session).single().output.endsWith(guide))
    }

    @Test
    fun `a failed open opens nothing, and a kit without tools only hands over its guide`() = runBlocking {
        val llm = ScriptedLlm(calls(open("c1", "nope"), open("c2", "draw")), answer("好"))
        val session = Session()

        loop(llm).run(session, "画个图", now).toList()

        val (bad, good) = results(session)
        assertFalse(bad.ok)
        assertTrue(bad.output.contains("notice、draw"))
        assertEquals("打开了「draw」。\n\n写一个 html 代码块。", good.output)
        assertEquals(listOf(FakeWriteTool.NAME, OpenKitTool.NAME), llm.offered(1))
    }

    @Test
    fun `the catalogue lives in the description of open_kit`() {
        val t = OpenKitTool(kits)
        assertTrue(t.description.contains("- notice：他想让你盯着手机通知时开") && t.description.contains("- draw：他想看图时开"))
        assertEquals(setOf("notice"), OpenKitTool.opened(listOf(Turn(1, listOf(
            Item.UserMessage("x", now), open("a", "notice"), Item.ToolResult("a", "ok", ok = true),
            open("b", "draw"), Item.ToolResult("b", "没打开", ok = false)
        )))))
    }

    @Test
    fun `an open kit asks for at least its effort, only while it stays open`() = runBlocking {
        val llm = ScriptedLlm(calls(open("c1", "draw")), answer("画好了"), answer("在"))
        val session = Session()

        loop(llm, keep = 1).run(session, "画个图", now).toList()
        loop(llm, keep = 1).run(session, "还在吗", now).toList()

        assertEquals(null, llm.requests[0].effortFloor)
        assertEquals(Effort.HIGH, llm.requests[1].effortFloor)
        assertEquals("下一轮包收了，照配置", null, llm.requests[2].effortFloor)
    }
}
