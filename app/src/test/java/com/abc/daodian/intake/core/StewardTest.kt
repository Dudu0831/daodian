package com.abc.daodian.intake.core

import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.inbox.InboxState.DONE
import com.abc.daodian.intake.core.inbox.InboxState.FAILED
import com.abc.daodian.intake.core.inbox.InboxState.PENDING
import com.abc.daodian.intake.core.rule.RuleDraft
import com.abc.daodian.intake.core.steward.Decision.HandOff
import com.abc.daodian.intake.core.steward.Decision.Ignore
import com.abc.daodian.intake.core.steward.DecisionBuffer
import com.abc.daodian.intake.core.steward.Destination
import com.abc.daodian.intake.core.steward.Settlement
import com.abc.daodian.intake.core.steward.StewardInput
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 管家的决定怎么收、怎么算，输入怎么拼 */
class StewardTest {

    private fun inboxNotice(id: Long, rules: Set<Long>, attempts: Int = 0, redacted: Boolean = false) =
        InboxNotice(id, SMS, 1_761_471_720_000, "菜鸟驿站", "取件码 3-2-1088", null, redacted, rules, attempts)

    private val n1 = inboxNotice(1, setOf(1, 2))
    private val n2 = inboxNotice(2, setOf(3))
    private fun buffer(vararg batch: InboxNotice = arrayOf(n1, n2)) = DecisionBuffer(batch.toList(), listOf("ledger", "reminder"))

    @Test
    fun `wrong decisions come back with the reason`() {
        val b = buffer()
        assertTrue(b.offer(HandOff(9, emptySet(), "reminder", ""))!!.contains("n9 不在这一批"))
        assertTrue(b.offer(HandOff(1, setOf(3), "reminder", ""))!!.contains("r3 不是 n1"))
        assertTrue(b.offer(HandOff(1, emptySet(), "parcel", ""))!!.contains("没有「parcel」这个去处"))
        assertTrue(b.offer(Ignore(1, emptySet(), " "))!!.contains("why 是空的"))
        assertEquals(mapOf(1L to setOf(1L, 2L), 2L to setOf(3L)), b.remaining())
    }

    @Test
    fun `a rule cannot be both handed off and ignored`() {
        val b = buffer()
        assertNull(b.offer(HandOff(1, setOf(1), "reminder", "18:00 提醒取快递")))
        assertTrue(b.offer(Ignore(1, setOf(1), "广告"))!!.contains("已经交出去了"))
        assertNull(b.offer(Ignore(1, emptySet(), "不是账")))
        assertTrue(b.offer(HandOff(1, setOf(2), "ledger", ""))!!.contains("已经标了不相关"))
        assertNull(b.offer(Ignore(2, emptySet(), "广告")))
        assertTrue(b.offer(HandOff(2, emptySet(), "ledger", ""))!!.contains("没有可交的"))
        val s = b.settle()
        assertTrue(HandOff(1, setOf(1), "reminder", "18:00 提醒取快递") in s.decisions)
        assertTrue(Ignore(1, setOf(2), "不是账") in s.decisions)
    }

    @Test
    fun `handing the same notice to the same place twice merges, the later note wins`() {
        val b = buffer()
        b.offer(HandOff(1, setOf(1), "reminder", "先写的"))
        b.offer(HandOff(1, setOf(2), "reminder", " 后写的 "))
        assertEquals(listOf(HandOff(1, setOf(1, 2), "reminder", "后写的")), b.settle().decisions)
    }

    @Test
    fun `one notice can go to several places`() {
        val b = buffer()
        assertNull(b.offer(HandOff(1, emptySet(), "ledger", "")))
        assertNull(b.offer(HandOff(1, emptySet(), "reminder", "25 号提醒还款")))
        assertTrue(b.offer(Ignore(1, emptySet(), "不相关"))!!.contains("不用再标"))
        assertEquals(setOf("ledger", "reminder"), b.settle().decisions.map { (it as HandOff).to }.toSet())
    }

    @Test
    fun `covered is done, short goes back with one more try, the third is failed`() {
        val n3 = inboxNotice(3, setOf(4), attempts = 2)
        val b = buffer(n1, n2, n3)
        b.offer(HandOff(1, setOf(1), "reminder", ""))
        b.offer(Ignore(1, setOf(2), "广告"))
        assertEquals(mapOf(2L to setOf(3L), 3L to setOf(4L)), b.remaining())
        val settled = b.settle().notices.associate { it.id to (it.state to it.attempts) }
        assertEquals(DONE to 0, settled[1])
        assertEquals(PENDING to 1, settled[2])
        assertEquals(FAILED to 3, settled[3])
    }

    @Test
    fun `a failed run writes nothing and costs no try`() {
        val s = Settlement.failed(listOf(n1, inboxNotice(3, setOf(4), attempts = 2)))
        assertTrue(s.decisions.isEmpty())
        assertEquals(listOf(PENDING to 0, PENDING to 2), s.notices.map { it.state to it.attempts })
    }

    private val destinations = listOf(
        Destination("ledger", "记账", "付款、收款、退款、转账的通知"),
        Destination("reminder", "提醒", "要你去做的事；附言写清几点")
    )
    private val r1 = rule(1, RuleDraft(name = "快递", apps = listOf(SMS), bodyAny = listOf("取件码"), soft = "18:00 提醒取"))
    private val r2 = rule(2, RuleDraft(name = "招行", apps = listOf(CMB), soft = "交给记账"))

    @Test
    fun `the standing part is byte for byte the same whatever order the rules come in`() {
        val text = StewardInput.standing(destinations, listOf(r2, r1))
        assertEquals(text, StewardInput.standing(destinations, listOf(r1, r2)))
        assertEquals(
            """
            去处（hand_off 的 to 填 id）：
            - ledger · 记账：付款、收款、退款、转账的通知
            - reminder · 提醒：要你去做的事；附言写清几点

            规则（编号 · 名字：软规则）：
            - r1 · 快递：18:00 提醒取
            - r2 · 招行：交给记账
            """.trimIndent(),
            text
        )
        assertNotEquals(text, StewardInput.standing(destinations, listOf(r1, r2.copy(soft = "交给记账和提醒"))))
    }

    @Test
    fun `the batch shows each notice and hides what the system hid`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val text = StewardInput.batch(listOf(inboxNotice(5, setOf(2, 1), redacted = true), n2), NAMES, zone)
        assertTrue(text.startsWith("这一批 2 条："))
        assertTrue(text.contains("n2 · 短信 · 10月26日 17:42 · 中了 r3\n标题：菜鸟驿站\n正文：取件码 3-2-1088"))
        assertTrue(text.contains("n5 · 短信 · 10月26日 17:42 · 中了 r1、r2\n标题：菜鸟驿站\n正文：（被系统遮蔽了，看不清）"))
        assertFalse(text.substringAfter("n5").contains("3-2-1088"))
        assertTrue(text.indexOf("n2 ·") < text.indexOf("n5 ·"))
    }
}
