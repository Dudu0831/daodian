package com.abc.daodian.intake.assistant

import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.CAINIAO
import com.abc.daodian.intake.core.FakeRules
import com.abc.daodian.intake.core.NAMES
import com.abc.daodian.intake.core.SMS
import com.abc.daodian.intake.core.WECHAT
import com.abc.daodian.intake.core.notice
import com.abc.daodian.intake.core.rule.RuleCheck
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolOutcome
import com.abc.daodian.shared.apps.AppCatalog
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存里的那份：三个 app、通知栏里几条、最近的标题 */
class FakeDesk(
    override val rules: FakeRules = FakeRules(),
    private val active: List<Notice> = emptyList(),
    private val titles: Map<String, List<String>> = emptyMap()
) : RuleDesk {
    override val names = NAMES
    override suspend fun apps() = listOf(AppCatalog.App(SMS, "短信"), AppCatalog.App(CAINIAO, "菜鸟"), AppCatalog.App(WECHAT, "微信"))
    override suspend fun recentTitles(pkg: String) = titles[pkg].orEmpty()
    override suspend fun samples() = active
    override suspend fun counts() = mapOf(1L to 3)
}

class IntakeToolsTest {

    private val ctx = ToolContext(ZonedDateTime.of(2026, 10, 27, 15, 0, 0, 0, ZoneId.of("Asia/Shanghai")), "")

    private val desk = FakeDesk(
        active = listOf(
            notice(SMS, "菜鸟驿站", "取件码 3-2-1088", key = "a"),
            notice(SMS, "10086", "您的验证码是 1234", key = "b"),
            notice(WECHAT, "老婆(2条)", "[2条]老婆: 到点 买酱油", key = "c")
        ),
        titles = mapOf(WECHAT to listOf("老婆", "工作群"))
    )
    private val tools = IntakeTools.of(desk).associateBy { it.name }

    private fun run(name: String, args: String): ToolOutcome = runBlocking { tools.getValue(name).execute(args, ctx) }

    private val parcel =
        """{"name":"快递到了提醒取","apps":["$SMS","$CAINIAO"],"title":null,"body_any":["取件码","驿站"],"body_none":["验证码"],"starts_with":null,"soft":"快递到了当天 18:00 提醒我取"}"""

    @Test
    fun `the six tools are the ones the prompt names`() {
        assertEquals(IntakeTools.NAMES, tools.keys)
        tools.values.forEach { t: Tool -> assertTrue(t.name, t.parameters["additionalProperties"] == false) }
    }

    @Test
    fun `apps can be looked up by name or package`() {
        assertEquals("菜鸟 · $CAINIAO", run("list_apps", """{"query":"菜鸟"}""").output)
        assertEquals(3, run("list_apps", """{"query":null}""").output.lines().size)
        assertTrue(run("list_apps", """{"query":"银行"}""").output.contains("没有"))
    }

    @Test
    fun `recent senders lists cleaned titles`() {
        assertEquals("微信 最近的标题：「老婆」、「工作群」", run("recent_senders", """{"app":"$WECHAT"}""").output)
        assertTrue(run("recent_senders", """{"app":"$SMS"}""").output.startsWith("最近没见过 短信"))
    }

    @Test
    fun `preview counts what the hard conditions would let in`() {
        val out = run("preview_rule", parcel).output
        assertEquals("现在通知栏和收件箱里对得上 1 条：短信 1 条（菜鸟驿站）。", out)
        val wife = """{"apps":["$WECHAT"],"title":{"text":"老婆","exact":true},"body_any":[],"body_none":[],"starts_with":"到点"}"""
        assertTrue(run("preview_rule", wife).output.contains("微信 1 条（老婆）"))
    }

    @Test
    fun `add rule goes through the gate, then reads back in full`() = runBlocking {
        val out = run("add_rule", parcel)
        assertTrue(out.output, out.ok)
        assertEquals(1L, out.ref)
        assertEquals(
            "建好了：r1「快递到了提醒取」。\n短信、菜鸟（含 取件码 / 驿站，不含 验证码） → 快递到了当天 18:00 提醒我取\n现在通知栏和收件箱里对得上 1 条：短信 1 条（菜鸟驿站）",
            out.output
        )
        assertEquals("快递到了提醒取", desk.rules.all().first().single().name)

        val trace = IntakeTrace.of(ToolTrace("add_rule", parcel, TraceState.OK, out.output, out.ref))!!
        assertEquals("监听", trace.settled)
        assertEquals("短信、菜鸟（含 取件码 / 驿站，不含 验证码） → 快递到了当天 18:00 提醒我取 · 现在通知栏和收件箱里对得上 1 条", trace.text)
    }

    @Test
    fun `a rule the gate refuses is not written and says why`() = runBlocking {
        val bad = parcel.replace("\"$CAINIAO\"", "\"com.taobao\"").replace("\"驿站\"", "\"码\"")
        val out = run("add_rule", bad)
        assertFalse(out.ok)
        assertTrue(out.output, out.output.startsWith("没建：") && out.output.contains("com.taobao") && out.output.contains("「码」"))
        assertTrue(desk.rules.all().first().isEmpty())
        assertEquals("没建成", IntakeTrace.of(ToolTrace("add_rule", bad, TraceState.FAILED, out.output, null))!!.settled)

        repeat(RuleCheck.MAX_RULES) { run("add_rule", parcel) }
        assertTrue(run("add_rule", parcel).output.contains("规则满了"))
    }

    @Test
    fun `stop and list`() = runBlocking {
        run("add_rule", parcel)
        assertTrue(run("list_rules", "{}").output.endsWith("启用着 · 进来过 3 条"))
        val stop = run("stop_rule", """{"id":1}""")
        assertEquals("停了：r1「快递到了提醒取」。", stop.output)
        assertEquals("r1「快递到了提醒取」", IntakeTrace.of(ToolTrace("stop_rule", "{}", TraceState.OK, stop.output, 1))!!.text)
        assertTrue(run("list_rules", "{}").output.contains("停了"))
        assertTrue(run("stop_rule", """{"id":1}""").output.contains("本来就停着"))
        assertTrue(run("stop_rule", """{"id":9}""").output.contains("没有 r9"))
    }
}
