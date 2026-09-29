package com.abc.daodian.ledger.tools

import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceState
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

    @Test
    fun `categories and tags do not share names`() {
        // 新二级撞上已有标签：打回，让它打那个标签
        val sub = LedgerGuard.pickCategory("餐饮/约会", Direction.OUT, categories, true, tagNames = listOf("约会"))
        assertTrue(sub is LedgerGuard.Check.No)
        assertTrue((sub as LedgerGuard.Check.No).reason.contains("标签「约会」"))
        // 新标签撞上已有类别：打回，让它用那个类别
        val clash = LedgerGuard.tagClash(listOf("#外卖"), emptyList(), categories)
        assertNotNull(clash)
        assertTrue(clash!!, clash.contains("餐饮/外卖"))
        // 已经有的标签不查；不撞的照常
        assertNull(LedgerGuard.tagClash(listOf("外卖"), listOf("外卖"), categories))
        assertNull(LedgerGuard.tagClash(listOf("约会"), emptyList(), categories))
    }

    @Test
    fun `organizer tags only when the setting is on, and only with tags he already has`() {
        val json = """{"expenses":[${expense("1", "197.00", "日用/理发").replace("\"tags\":[]", "\"tags\":[\"约会\",\"理发\",\"新的\"]")}],"ignore":[],"unreadable":[]}"""
        fun hasTagsParam(tool: RecordExpensesTool): Boolean {
            val expenses = (tool.parameters["properties"] as Map<*, *>)["expenses"] as Map<*, *>
            return ((expenses["items"] as Map<*, *>)["properties"] as Map<*, *>).containsKey("tags")
        }

        // 关着（默认）：参数里没有 tags，给了也不挂
        val off = FakeLedger(raws, categories, tags = listOf("约会"))
        val offTool = RecordExpensesTool(off, { "test@v" }, { zone })
        assertFalse(hasTagsParam(offTool))
        assertTrue(runBlocking { offTool.execute(json, ctx) }.ok)
        assertEquals(emptyList<String>(), off.txns.values.single().tags)

        // 开着：只挂已有的；新名字（撞不撞类别都一样）不挂，也不连累这一笔
        val on = FakeLedger(raws, categories, tags = listOf("约会"))
        val onTool = RecordExpensesTool(on, { "test@v" }, { zone }, tagging = true)
        assertTrue(hasTagsParam(onTool))
        assertTrue(runBlocking { onTool.execute(json, ctx) }.ok)
        assertEquals(listOf("约会"), on.txns.values.single().tags)
    }

    @Test
    fun `organizer prompt only teaches tagging when the setting is on`() {
        assertTrue(LedgerPrompt.ORGANIZE.contains("标签不归你打"))
        assertFalse(LedgerPrompt.ORGANIZE.contains("用户打过的标签"))
        assertTrue(LedgerPrompt.ORGANIZE_TAGGING.contains("只用「用户打过的标签」里已有的"))
        assertFalse(LedgerPrompt.ORGANIZE_TAGGING.contains("标签不归你打"))
        assertEquals("${LedgerPrompt.VERSION}+tags", LedgerPrompt.version(true))
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
    fun `tags go on and come off in chat, and the trace says which`() {
        val backend = FakeLedger(raws, categories, tags = listOf("约会"))
        record(
            backend,
            """{"expenses":[${expense("1", "197.00", "日用/理发")},${expense("2,3", "319.40", "日用/超市日用")}],"ignore":[],"unreadable":[]}"""
        )
        val (a, b) = backend.txns.keys.toList()
        val tool = UpdateExpensesTool(backend) { zone }
        fun change(id: Long, add: String = "[]", remove: String = "[]") =
            """{"txn_id":$id,"category":null,"split":null,"summary":null,"note":null,"add_tags":$add,"remove_tags":$remove,
            "merchant":null,"remember_merchant":false,"confirm":false,"void_reason":null,"refund_of":null}"""
        fun run(vararg changes: String) = runBlocking { tool.execute("""{"changes":[${changes.joinToString(",")}]}""", ctx) }

        // 一次打两笔：只动标签的不写类别，痕上合成一句
        val both = run(change(a, add = """["约会"]"""), change(b, add = """["约会"]"""))
        assertTrue(both.output, both.ok)
        assertEquals(listOf("约会"), backend.txns.getValue(a).tags)
        assertTrue(both.output, both.output.lineSequence().first().contains("一笔 197.00 · 标签 +约会；"))
        val trace = LedgerTrace.of(ToolTrace(UpdateExpensesTool.NAME, "", TraceState.OK, both.output, both.ref))!!
        assertEquals("改了 2 笔 · 标签 +约会", trace.text)

        // 取下；没挂着的取不了，撞了类别的打不上
        assertTrue(run(change(a, remove = """["约会"]""")).ok)
        assertEquals(emptyList<String>(), backend.txns.getValue(a).tags)
        val notThere = run(change(a, remove = """["请客"]"""))
        assertFalse(notThere.output, notThere.ok)
        val clash = run(change(a, add = """["理发"]"""))
        assertFalse(clash.output, clash.ok)
        assertTrue(clash.output, clash.output.contains("日用/理发"))
    }

    @Test
    fun `income that was really a refund turns into one and follows the original's category`() {
        // 9-28 真机：建行储蓄卡充电扣 3.00，没用完的 1.82 退回来，通知上写的是「收入」
        val charge = listOf(
            RawNote(51, "建设银行", at(18, 39), "动账提醒", "您尾号4918的储蓄账户9月22日18时38分支出人民币3.00元。点击查看>>", null, false, false),
            RawNote(52, "建设银行", at(22, 18), "动账提醒", "您尾号4918的储蓄账户9月22日22时17分收入人民币1.82元。点击查看>>", null, false, false),
        )
        val backend = FakeLedger(charge, categories)
        record(backend, """{"expenses":[${expense("51", "3.00", "其他")},${expense("52", "1.82", "工资", direction = "IN")}],"ignore":[],"unreadable":[]}""")
        val (out, back) = backend.txns.keys.toList()
        val tool = UpdateExpensesTool(backend) { zone }
        fun change(direction: String?, refundOf: Long?, category: String? = null) = runBlocking {
            tool.execute(
                """{"changes":[{"txn_id":$back,"category":${category?.let { "\"$it\"" }},"split":null,"summary":null,"note":null,
                "add_tags":[],"remove_tags":[],"merchant":null,"remember_merchant":false,"confirm":true,"void_reason":null,
                "direction":${direction?.let { "\"$it\"" }},"refund_of":$refundOf}]}""",
                ctx
            )
        }

        // 真机上卡住的那一下：方向没改就挂 refund_of —— 打回时告诉它连方向一起改
        val stuck = change(direction = null, refundOf = out)
        assertFalse(stuck.output, stuck.ok)
        assertTrue(stuck.output, stuck.output.contains("REFUND"))
        // 改成退款，既没挂原笔也没给支出类别：收入类别对不上，打回
        assertFalse(change(direction = "REFUND", refundOf = null).ok)

        // 挂上原笔：模型给的类别不看，跟原笔走
        val ok = change(direction = "REFUND", refundOf = out, category = "工资")
        assertTrue(ok.output, ok.ok)
        val refund = backend.txns.getValue(back)
        assertEquals(Direction.REFUND, refund.direction)
        assertEquals(out, refund.refundOf)
        assertEquals("其他", refund.category)
        assertEquals(listOf(6L to 182L), backend.allocations.getValue(back))
        assertEquals(TxnState.CONFIRMED, refund.state)
        assertEquals("一笔 1.82 改成退款 → 其他", ok.output.lineSequence().first().substringAfter('：'))
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
private class FakeLedger(
    raws: List<RawNote>,
    private val cats: List<CategoryNode>,
    private val tags: List<String> = emptyList()
) : LedgerBackend {

    private val rawById = raws.associateBy { it.id }
    val txns = LinkedHashMap<Long, TxnBrief>()
    val allocations = HashMap<Long, List<Pair<Long?, Long>>>()
    val ignored = mutableListOf<Long>()
    val unreadable = mutableListOf<Long>()
    private var nextId = 100L

    override suspend fun raws(ids: Collection<Long>) = ids.mapNotNull { rawById[it] }
    override suspend fun categories() = cats
    override suspend fun tagNames() = tags
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
            // 换到另一边的类别：原来的不作数，放回未归类
            val kindTurned = c.direction != null && c.direction.categoryKind != t.direction.categoryKind
            if (kindTurned) allocations[c.txnId] = listOf(null to t.amount)
            c.split?.let { allocations[c.txnId] = it }
            c.categoryId?.let { allocations[c.txnId] = listOf(it to t.amount) }
            txns[c.txnId] = t.copy(
                direction = c.direction ?: t.direction,
                state = if (c.voidReason != null) TxnState.VOID else if (c.confirm) TxnState.CONFIRMED else t.state,
                category = c.categoryId?.let(::path) ?: if (kindTurned) null else t.category,
                refundOf = c.refundOf ?: t.refundOf,
                tags = (t.tags + c.addTags).distinct() - c.removeTags.toSet()
            )
        }
    }

    override suspend fun addCategory(name: String, parentId: Long?, kind: CategoryKind, actor: Actor) = 999L
}
