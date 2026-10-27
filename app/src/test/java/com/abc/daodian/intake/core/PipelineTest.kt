package com.abc.daodian.intake.core

import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.inbox.InboxState.DONE
import com.abc.daodian.intake.core.inbox.InboxState.FAILED
import com.abc.daodian.intake.core.inbox.InboxState.PENDING
import com.abc.daodian.intake.core.inbox.InboxState.WAITING
import com.abc.daodian.intake.core.pipeline.Catch
import com.abc.daodian.intake.core.pipeline.Pipeline
import com.abc.daodian.intake.core.pipeline.Stage
import com.abc.daodian.intake.core.rule.RuleDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收的四段，接内存里的规则和收件箱 */
class PipelineTest {

    private val parcel = rule(1, RuleDraft(name = "快递", apps = listOf(SMS), bodyAny = listOf("取件码", "驿站"), soft = "18:00 提醒取"))
    private val bank = rule(2, RuleDraft(name = "招行", apps = listOf(CMB), soft = "交给记账"))
    private val wife = rule(3, RuleDraft(name = "老婆", apps = listOf(WECHAT), title = "老婆", startsWith = "到点", soft = "建成提醒"))

    private val rules = FakeRules(listOf(parcel, bank, wife))
    private val inbox = FakeInbox()
    private val reread = mutableListOf<Notice>()
    private val pipeline = Pipeline.standard(rules, inbox, { reread += it }, now = { 0 })

    private fun run(n: Notice, last: Boolean = false): Catch? = runBlocking { pipeline.run(n, last) }

    @Test
    fun `apps nobody listens to never touch the inbox`() {
        assertNull(run(notice("com.taobao", "淘宝", "取件码 1234")))
        assertEquals(0, inbox.lookups)
    }

    @Test
    fun `listened but not matched is dropped`() {
        assertNull(run(notice(SMS, "10086", "话费余额 12 元")))
        assertNull(run(notice(WECHAT, "老婆", "晚上吃啥")))
        assertTrue(inbox.rows.isEmpty())
    }

    @Test
    fun `matched goes in once, pending, with the rules it matched`() {
        val c = run(notice(SMS, "菜鸟驿站", "取件码 3-2-1088"))
        assertNotNull(c)
        assertNull(run(notice(SMS, "菜鸟驿站", "取件码 3-2-1088")))
        val row = inbox.rows.values.single()
        assertEquals(PENDING, row.state)
        assertEquals(setOf(1L), row.rules)
        assertTrue(reread.isEmpty())
    }

    @Test
    fun `the chat code is checked after cleaning`() {
        assertNotNull(run(notice(WECHAT, "老婆(2条)", "[2条]老婆: 到点 带瓶酱油")))
        assertEquals(setOf(3L), inbox.rows.values.single().rules)
    }

    @Test
    fun `a rule made later picks up a notice already in, only the new rule is pending`() {
        run(notice(SMS, "95555", "招行信用卡 取件码 已寄出", key = "a"))
        inbox.set(1, DONE)
        rules.list = rules.list + rule(4, RuleDraft(name = "信用卡", apps = listOf(SMS), bodyAny = listOf("信用卡"), soft = "提醒还款"))
        assertNotNull(run(notice(SMS, "95555", "招行信用卡 取件码 已寄出", key = "a")))
        val row = inbox.rows.getValue(1)
        assertEquals(setOf(1L, 4L), row.rules)
        assertEquals(PENDING, row.state)

        inbox.set(1, FAILED, attempts = 3)
        rules.list = rules.list + rule(5, RuleDraft(name = "寄出", apps = listOf(SMS), bodyAny = listOf("已寄出"), soft = "x"))
        run(notice(SMS, "95555", "招行信用卡 取件码 已寄出", key = "a"))
        assertEquals(PENDING to 0, inbox.rows.getValue(1).let { it.state to it.attempts })
    }

    @Test
    fun `redacted waits, asks for a reread once, and the real text replaces it`() {
        val hidden = notice(SMS, "菜鸟驿站", "敏感数据已隐藏", redacted = true)
        run(hidden)
        assertEquals(WAITING, inbox.rows.values.single().state)
        assertEquals(1, reread.size)
        run(hidden.copy(how = "retry+5s"))
        assertEquals(1, reread.size)

        run(notice(SMS, "菜鸟驿站", "取件码 3-2-1088"))
        val row = inbox.rows.values.single()
        assertEquals(PENDING, row.state)
        assertFalse(row.redacted)
        assertEquals("取件码 3-2-1088", row.text)
    }

    @Test
    fun `redacted whose real text does not match is deleted`() {
        run(notice(SMS, "菜鸟驿站", "敏感数据已隐藏", redacted = true))
        assertNull(run(notice(SMS, "菜鸟驿站", "您的验证码是 1234")))
        assertTrue(inbox.rows.isEmpty())
    }

    @Test
    fun `still redacted on the last look goes to the steward as is`() {
        val hidden = notice(CMB, "招商银行", "敏感数据已隐藏", redacted = true)
        run(hidden)
        assertEquals(WAITING, inbox.rows.values.single().state)
        run(hidden.copy(how = "retry+120s"), last = true)
        assertEquals(PENDING, inbox.rows.values.single().state)
    }

    @Test
    fun `redacted that cannot match by app or title is dropped without the inbox`() {
        assertNull(run(notice(WECHAT, "老妈", "敏感数据已隐藏", redacted = true)))
        assertEquals(0, inbox.lookups)
    }

    @Test
    fun `stopped rules do not match`() {
        rules.list = rules.list.map { if (it.id == 1L) it.copy(enabled = false) else it }
        assertNull(run(notice(SMS, "菜鸟驿站", "取件码 3-2-1088")))
    }

    @Test
    fun `a stage can stop the line`() = runBlocking {
        val gate = Stage { null }
        assertNull(Pipeline(listOf(gate, Stage { error("不该走到这") })).run(notice()))
    }
}
