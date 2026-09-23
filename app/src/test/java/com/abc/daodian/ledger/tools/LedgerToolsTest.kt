package com.abc.daodian.ledger.tools

import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.ledger.domain.Actor
import com.abc.daodian.ledger.domain.CategoryKind
import com.abc.daodian.ledger.domain.CategoryNode
import com.abc.daodian.ledger.domain.Direction
import com.abc.daodian.ledger.domain.ExpenseDraft
import com.abc.daodian.ledger.domain.ExpenseQuery
import com.abc.daodian.ledger.domain.LedgerBackend
import com.abc.daodian.ledger.domain.LedgerGuard
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.domain.Money
import com.abc.daodian.ledger.domain.NewSubcategory
import com.abc.daodian.ledger.domain.RawNote
import com.abc.daodian.ledger.domain.RawVerdict
import com.abc.daodian.ledger.domain.TxnBrief
import com.abc.daodian.ledger.domain.TxnChange
import com.abc.daodian.ledger.domain.TxnSource
import com.abc.daodian.ledger.domain.TxnState
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 护栏和记账工具。存储是内存假货，原始通知是 9-22 真机抓到的原文 */
class LedgerToolsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 9, 22, 21, 0, 0, 0, zone)
    private val ctx = ToolContext(now, "")

    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 9, 22, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val raws = listOf(
        RawNote(1, "建设银行", at(19, 19), "动账提醒", "您尾号4918的储蓄账户9月22日19时18分支出人民币197.00元。点击查看>>", null, false, false),
        RawNote(2, "招商银行", at(19, 20), "招商银行", "信用卡通知：您尾号8837的招行信用卡消费319.40人民币。", null, false, false),
        RawNote(3, "掌上生活", at(19, 20), "交易提醒", "您在支付宝-山姆会员商店有一笔319.40人民币的消费已成功，点击查看详情", null, false, false),
        RawNote(4, "建设银行", at(11, 1), "月月领金", "支付1分钱，至高抽188元微信立减金！点击参与。", null, false, false),
        RawNote(5, "招商银行", at(20, 53), "招商银行", "敏感数据已隐藏", null, true, false),
    )

    private val categories = listOf(
        CategoryNode(1, "餐饮", null, CategoryKind.OUT),
        CategoryNode(2, "外卖", 1, CategoryKind.OUT),
        CategoryNode(3, "日用", null, CategoryKind.OUT),
        CategoryNode(4, "超市日用", 3, CategoryKind.OUT),
        CategoryNode(5, "理发", 3, CategoryKind.OUT),
        CategoryNode(6, "其他", null, CategoryKind.OUT),
        CategoryNode(7, "工资", null, CategoryKind.IN),
    )

    private fun record(backend: FakeLedger, json: String) =
        runBlocking { RecordExpensesTool(backend, { "test@v" }, { zone }).execute(json, ctx) }

    private fun expense(
        rawIds: String, amount: String, category: String? = null, ask: String? = null,
        direction: String = "OUT", occurredAt: String? = null
    ) = """{"raw_ids":[$rawIds],"direction":"$direction","amount":"$amount","occurred_at":${occurredAt?.let { "\"$it\"" }},
        "account":null,"channel":null,"merchant_raw":null,"merchant":null,"summary":"一笔","category":${category?.let { "\"$it\"" }},
        "tags":[],"confidence":0.9,"ask":${ask?.let { "\"$it\"" }},"refund_of":null}"""

    // ---------------- 护栏 ----------------

    @Test
    fun `amount must appear in the raw text, and card tails do not count`() {
        assertTrue(LedgerGuard.amountInRaw(19700, raws.take(1)))
        assertTrue(LedgerGuard.amountInRaw(31940, listOf(raws[1])))
        // 「尾号4918」「8837」里的数字不能被当成金额
        assertFalse(LedgerGuard.amountInRaw(4918_00, raws.take(1)))
        assertFalse(LedgerGuard.amountInRaw(18, raws.take(1)))
        assertFalse(LedgerGuard.amountInRaw(1900, raws.take(1)))
        // 遮蔽版里什么都找不到
        assertFalse(LedgerGuard.amountInRaw(100, listOf(raws[4])))
    }

    @Test
    fun `money parses yuan text into cents`() {
        assertEquals(31940L, Money.parseCents("319.40"))
        assertEquals(31940L, Money.parseCents("¥319.4"))
        assertEquals(123450L, Money.parseCents("1,234.50元"))
        assertNull(Money.parseCents("-5"))
        assertNull(Money.parseCents("1.234"))
        assertNull(Money.parseCents("abc"))
    }

    @Test
    fun `category must be a leaf of the right kind`() {
        // 只写一级 = 没细分，可以
        assertEquals(
            LedgerGuard.CategoryPick(3, null),
            (LedgerGuard.pickCategory("日用", Direction.OUT, categories, true) as LedgerGuard.Check.Ok).value
        )
        // 别的一级下已经有同名二级：不许另建
        assertTrue(LedgerGuard.pickCategory("其他/理发", Direction.OUT, categories, true) is LedgerGuard.Check.No)
        assertEquals(
            LedgerGuard.CategoryPick(4, null),
            (LedgerGuard.pickCategory("日用/超市日用", Direction.OUT, categories, true) as LedgerGuard.Check.Ok).value
        )
        assertEquals(
            LedgerGuard.CategoryPick(6, null),
            (LedgerGuard.pickCategory("其他", Direction.OUT, categories, true) as LedgerGuard.Check.Ok).value
        )
        // 新二级：允许时顺手建
        assertEquals(
            NewSubcategory(1, "夜宵"),
            (LedgerGuard.pickCategory("餐饮/夜宵", Direction.OUT, categories, true) as LedgerGuard.Check.Ok).value.newSub
        )
        assertTrue(LedgerGuard.pickCategory("餐饮/夜宵", Direction.OUT, categories, false) is LedgerGuard.Check.No)
        // 一级不能自己编；收入不能挂支出类别
        assertTrue(LedgerGuard.pickCategory("宠物/猫粮", Direction.OUT, categories, true) is LedgerGuard.Check.No)
        assertTrue(LedgerGuard.pickCategory("日用/理发", Direction.IN, categories, true) is LedgerGuard.Check.No)
        // 转移不挂类别
        assertNull((LedgerGuard.pickCategory("日用/理发", Direction.TRANSFER, categories, true) as LedgerGuard.Check.Ok).value.categoryId)
    }

    // ---------------- record_expenses ----------------

    @Test
    fun `cmb and its credit app merge into one expense, marketing is ignored`() {
        val backend = FakeLedger(raws, categories)
        val out = record(
            backend, """{"expenses":[${expense("2,3", "319.40", "日用/超市日用")}, ${expense("1", "197.00", ask = "197 是什么？")}],
                "ignore":[{"raw_id":4,"reason":"营销"}],"unreadable":[{"raw_id":5,"reason":"遮蔽"}]}"""
        )
        assertTrue(out.output, out.ok)
        assertEquals(2, backend.txns.size)
        val sam = backend.txns.values.first { it.amount == 31940L }
        assertEquals(listOf(2L, 3L), sam.rawIds)
        assertEquals(TxnState.AUTO, sam.state)
        assertEquals(TxnState.PENDING, backend.txns.values.first { it.amount == 19700L }.state)
        assertEquals(listOf(4L), backend.ignored)
        assertEquals(listOf(5L), backend.unreadable)
    }

    @Test
    fun `made-up amount is rejected for that expense only`() {
        val backend = FakeLedger(raws, categories)
        val out = record(backend, """{"expenses":[${expense("1", "197.50", "日用/理发")}, ${expense("2,3", "319.40", "日用/超市日用")}],
            "ignore":[],"unreadable":[]}""")
        assertFalse(out.ok)
        assertTrue(out.output, out.output.contains("找不到"))
        assertEquals(1, backend.txns.size)
    }

    @Test
    fun `one notification cannot be in two expenses`() {
        val backend = FakeLedger(raws, categories)
        val out = record(backend, """{"expenses":[${expense("2", "319.40", "日用/超市日用")}, ${expense("2,3", "319.40", "日用/超市日用")}],
            "ignore":[],"unreadable":[]}""")
        assertFalse(out.ok)
        assertEquals(1, backend.txns.size)
    }

    @Test
    fun `rerun updates in place but never touches a confirmed expense`() {
        val backend = FakeLedger(raws, categories)
        record(backend, """{"expenses":[${expense("1", "197.00", ask = "是什么？")}],"ignore":[],"unreadable":[]}""")
        val id = backend.txns.keys.single()

        // 重跑：同一条通知，这次有把握了 → 就地更新，不新增
        record(backend, """{"expenses":[${expense("1", "197.00", "日用/理发")}],"ignore":[],"unreadable":[]}""")
        assertEquals(setOf(id), backend.txns.keys)
        assertEquals(TxnState.AUTO, backend.txns.getValue(id).state)

        // 用户确认过之后，整理再怎么交都不动它
        backend.txns[id] = backend.txns.getValue(id).copy(state = TxnState.CONFIRMED)
        val out = record(backend, """{"expenses":[${expense("1", "197.00", "餐饮/外卖")}],"ignore":[],"unreadable":[]}""")
        assertFalse(out.ok)
        assertEquals("日用/理发", backend.txns.getValue(id).category)
    }

    @Test
    fun `occurred time far from the notification is rejected`() {
        val backend = FakeLedger(raws, categories)
        val out = record(backend, """{"expenses":[${expense("1", "197.00", "日用/理发", occurredAt = "2026-09-20T19:18:00+08:00")}],
            "ignore":[],"unreadable":[]}""")
        assertFalse(out.ok)
        assertTrue(backend.txns.isEmpty())
    }

    @Test
    fun `uncategorized expense without a question gets one`() {
        val backend = FakeLedger(raws, categories)
        record(backend, """{"expenses":[${expense("1", "197.00")}],"ignore":[],"unreadable":[]}""")
        val t = backend.txns.values.single()
        assertEquals(TxnState.PENDING, t.state)
        assertNotNull(t.ask)
    }

    @Test
    fun `refund in the same batch finds its original by merchant`() {
        val cloud = listOf(
            RawNote(41, "掌上生活", at(0, 50), "交易提醒", "您在财付通-四川云慧充有一笔3.00人民币的消费已成功", null, false, false),
            RawNote(42, "掌上生活", at(0, 59), "退款提醒", "您在财付通-四川云慧充有一笔1.49人民币的退货已成功", null, false, false),
        )
        val backend = FakeLedger(cloud, categories)
        fun e(raw: Int, amount: String, dir: String, category: String?) =
            """{"raw_ids":[$raw],"direction":"$dir","amount":"$amount","occurred_at":null,"account":null,"channel":"财付通",
            "merchant_raw":"财付通-四川云慧充","merchant":"四川云慧充","summary":"云慧充","category":${category?.let { "\"$it\"" }},
            "tags":[],"confidence":0.8,"ask":null,"refund_of":null,"refund_of_raw":null}"""
        val out = record(backend, """{"expenses":[${e(41, "3.00", "OUT", "其他")}, ${e(42, "1.49", "REFUND", null)}],"ignore":[],"unreadable":[]}""")
        assertTrue(out.output, out.ok)
        val refund = backend.txns.values.single { it.direction == Direction.REFUND }
        val origin = backend.txns.values.single { it.direction == Direction.OUT }
        assertEquals(origin.id, refund.refundOf)
        assertEquals("其他", refund.category)
    }

    // ---------------- update / add ----------------

    @Test
    fun `split must add up to the amount, and categorizing confirms`() {
        val backend = FakeLedger(raws, categories)
        record(backend, """{"expenses":[${expense("2,3", "319.40", "日用/超市日用")}],"ignore":[],"unreadable":[]}""")
        val id = backend.txns.keys.single()
        val tool = UpdateExpensesTool(backend) { zone }

        fun change(split: String) = """{"changes":[{"txn_id":$id,"category":null,"split":$split,"summary":null,"note":null,
            "add_tags":[],"merchant":null,"remember_merchant":false,"confirm":false,"void_reason":null,"refund_of":null}]}"""

        val bad = runBlocking {
            tool.execute(change("""[{"category":"餐饮/外卖","amount":"200"},{"category":"日用/超市日用","amount":"100"}]"""), ctx)
        }
        assertFalse(bad.ok)

        val good = runBlocking {
            tool.execute(change("""[{"category":"餐饮/外卖","amount":"200"},{"category":"日用/超市日用","amount":"119.40"}]"""), ctx)
        }
        assertTrue(good.output, good.ok)
        assertEquals(TxnState.CONFIRMED, backend.txns.getValue(id).state)
        assertEquals(listOf(2L to 20000L, 4L to 11940L), backend.allocations.getValue(id))
    }

    @Test
    fun `chat expense is confirmed and needs no raw`() {
        val backend = FakeLedger(raws, categories)
        val out = runBlocking {
            AddExpenseTool(backend) { zone }.execute(
                """{"direction":"OUT","amount":"5","occurred_at":null,"day_only":false,"summary":"停车","category":"其他",
                "account":null,"channel":null,"merchant":null,"tags":[],"note":null,"raw_ids":[]}""", ctx
            )
        }
        assertTrue(out.output, out.ok)
        val t = backend.txns.values.single()
        assertEquals(TxnState.CONFIRMED, t.state)
        assertEquals(TxnSource.CHAT, t.source)
        assertEquals(500L, t.amount)
    }
}

/** 内存账本：只实现工具用得到的那点行为 */
private class FakeLedger(raws: List<RawNote>, private val cats: List<CategoryNode>) : LedgerBackend {

    private val rawById = raws.associateBy { it.id }
    val txns = LinkedHashMap<Long, TxnBrief>()
    val allocations = HashMap<Long, List<Pair<Long?, Long>>>()
    val ignored = mutableListOf<Long>()
    val unreadable = mutableListOf<Long>()
    private var nextId = 100L

    override suspend fun raws(ids: Collection<Long>) = ids.mapNotNull { rawById[it] }
    override suspend fun categories() = cats
    override suspend fun txns(ids: Collection<Long>) = ids.mapNotNull { txns[it] }
    override suspend fun txnsOfRaws(rawIds: Collection<Long>): Map<Long, TxnBrief> =
        rawIds.mapNotNull { r -> txns.values.firstOrNull { r in it.rawIds && it.state != TxnState.VOID }?.let { r to it } }.toMap()
    override suspend fun query(q: ExpenseQuery) = txns.values.toList()

    private fun path(id: Long?) = LedgerText.path(id, cats)

    override suspend fun record(
        drafts: List<ExpenseDraft>, ignored: List<RawVerdict>, unreadable: List<RawVerdict>, actor: Actor, parsedBy: String
    ): List<Long> {
        val ids = drafts.map { d ->
            val id = d.replaces ?: nextId++
            val state = when {
                d.confirmed -> TxnState.CONFIRMED
                d.ask != null -> TxnState.PENDING
                else -> TxnState.AUTO
            }
            txns[id] = TxnBrief(
                id, d.direction, d.amount, d.occurredAt, 0, d.summary, d.note, path(d.categoryId), null, d.channel,
                d.merchant, d.merchantRaw, state, if (d.rawIds.isEmpty()) TxnSource.CHAT else TxnSource.NOTIFICATION,
                d.ask, d.tags, d.rawIds, d.refundOf
            )
            allocations[id] = listOf(d.categoryId to d.amount)
            id
        }
        this.ignored += ignored.map { it.rawId }
        this.unreadable += unreadable.map { it.rawId }
        return ids
    }

    override suspend fun update(changes: List<TxnChange>, actor: Actor) {
        changes.forEach { c ->
            val t = txns.getValue(c.txnId)
            c.split?.let { allocations[c.txnId] = it }
            c.categoryId?.let { allocations[c.txnId] = listOf(it to t.amount) }
            txns[c.txnId] = t.copy(
                state = if (c.voidReason != null) TxnState.VOID else if (c.confirm) TxnState.CONFIRMED else t.state,
                category = c.categoryId?.let(::path) ?: t.category,
                refundOf = c.refundOf ?: t.refundOf
            )
        }
    }

    override suspend fun addCategory(name: String, parentId: Long?, kind: CategoryKind, actor: Actor) = 999L
}
