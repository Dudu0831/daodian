package com.abc.daodian.harness.builtin.ledger

import com.abc.daodian.harness.builtin.ledger.LedgerJson.account
import com.abc.daodian.harness.builtin.ledger.LedgerJson.longOrNull
import com.abc.daodian.harness.builtin.ledger.LedgerJson.longs
import com.abc.daodian.harness.builtin.ledger.LedgerJson.objects
import com.abc.daodian.harness.builtin.ledger.LedgerJson.strings
import com.abc.daodian.harness.builtin.ledger.LedgerJson.text
import com.abc.daodian.harness.tool.Tool
import com.abc.daodian.harness.tool.ToolContext
import com.abc.daodian.harness.tool.ToolEffect
import com.abc.daodian.harness.tool.ToolOutcome
import java.time.ZoneId

/**
 * 整理 agent 的主工具：把一批原始通知变成流水，一次落一批。
 *
 * 每笔都要过 [LedgerGuard]；过不了的**只打回那一笔**（通知留在待整理），原因回给模型，
 * 其余照常落库。重跑时按原始通知找到旧流水就地更新 —— 天然幂等；用户确认过的不动。
 *
 * 成功时 [ToolOutcome.payload] 是 [Recorded]。
 */
class RecordExpensesTool(
    private val backend: LedgerBackend,
    /** 写进每笔流水的 parsed_by：模型名 + 提示词版本 */
    private val parsedBy: () -> String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : Tool {

    data class Recorded(val txnIds: List<Long>, val ignored: Int, val unreadable: Int, val rejected: Int)

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "把原始通知整理成流水，一次交一批。同一笔钱的几条通知（招行+掌上生活、同一条推了两遍）合成一笔，raw_ids 全列上。" +
            "不是账的（营销、优惠券、还款提醒、空通知）放进 ignore；正文被遮蔽读不出金额的放进 unreadable。" +
            "每条原始通知都要有个去处。"

    override val parameters: Map<String, Any?> = LedgerJson.obj(
        "expenses" to LedgerJson.arr(
            "流水",
            LedgerJson.obj(
                "raw_ids" to LedgerJson.arr("这笔来自哪几条原始通知（raw#后面的数字），至少一条", mapOf("type" to "integer")),
                "direction" to LedgerJson.enumOf(
                    "OUT 支出 / IN 收入 / REFUND 退款 / TRANSFER 转移（信用卡还款、自己账户互转、余额宝进出，不算花钱）",
                    LedgerJson.DIRECTIONS
                ),
                "amount" to LedgerJson.str("金额，元，照原文抄，如「319.40」"),
                "occurred_at" to LedgerJson.strOrNull(
                    "发生时刻 ISO-8601 带偏移。正文里写了时刻（建行「9月22日19时18分」）就填它；没写就 null，用通知时刻"
                ),
                "account" to LedgerJson.ACCOUNT,
                "channel" to LedgerJson.strOrNull("支付渠道：支付宝 / 财付通 / 花呗 / 云闪付…；看不出来 null"),
                "merchant_raw" to LedgerJson.strOrNull("原文里的商户串，原样抄，如「支付宝-山姆会员商店」；没有 null"),
                "merchant" to LedgerJson.strOrNull("规范后的商户名，如「山姆会员商店」；没有商户 null"),
                "summary" to LedgerJson.str("一句人话，12 字以内，用户在列表里看的就是它，如「山姆买日用」「建行卡一笔支出」"),
                "category" to LedgerJson.strOrNull(
                    "类别路径「一级/二级」，如「日用/超市日用」。一级只能用现有的；二级没有合适的可以写个新的。" +
                        "没把握就 null（未归类）并填 ask。TRANSFER 填 null"
                ),
                "tags" to LedgerJson.arr("标签，没有就空数组", mapOf("type" to "string")),
                "confidence" to LedgerJson.num("对类别有多大把握，0~1"),
                "ask" to LedgerJson.strOrNull("没把握时想问用户的一句话（带上时间和金额，他才认得出是哪笔）；有把握就 null"),
                "refund_of" to LedgerJson.intOrNull("退款退的是哪一笔已有流水（#后面的数字）；不是退款或找不到 null"),
                "refund_of_raw" to LedgerJson.intOrNull(
                    "原笔还没编号（和退款在同一批里）时，填原笔那条消费通知的 raw 编号；用了 refund_of 就填 null"
                )
            )
        ),
        "ignore" to LedgerJson.arr(
            "不是账的原始通知",
            LedgerJson.obj("raw_id" to LedgerJson.int("raw#后面的数字"), "reason" to LedgerJson.str("为什么不是账，几个字"))
        ),
        "unreadable" to LedgerJson.arr(
            "是账但读不出金额的（正文被遮蔽）",
            LedgerJson.obj("raw_id" to LedgerJson.int("raw#后面的数字"), "reason" to LedgerJson.str("几个字"))
        )
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val root = LedgerJson.parse(arguments)
            ?: return ToolOutcome("没记。参数不是合法的 JSON：${arguments.take(200)}", ok = false)
        val zone = zone()
        val items = root.objects("expenses")
        val ignoreItems = root.objects("ignore")
        val unreadableItems = root.objects("unreadable")

        val allRawIds = (items.flatMap { it.longs("raw_ids") } +
            (ignoreItems + unreadableItems).mapNotNull { it.longOrNull("raw_id") }).toSet()
        val raws = backend.raws(allRawIds).associateBy { it.id }
        val categories = backend.categories()
        val owners = backend.txnsOfRaws(allRawIds)
        val refundTargets = backend.txns(items.mapNotNull { it.longOrNull("refund_of") }).associateBy { it.id }

        val claimed = HashSet<Long>()
        val drafts = mutableListOf<ExpenseDraft>()
        val rejects = mutableListOf<String>()
        // 同一批里要新建的二级：两笔都写「餐饮/夜宵」时只建一个
        val newSubs = HashMap<Pair<Long, String>, NewSubcategory>()

        items.forEachIndexed { i, e ->
            val label = "第 ${i + 1} 笔"
            when (val v = draftOf(e, raws, owners, categories, refundTargets, claimed, zone)) {
                is LedgerGuard.Check.No -> rejects += "$label：${v.reason}"
                is LedgerGuard.Check.Ok -> {
                    val d = v.value
                    val sub = d.newSubcategory?.let { s -> newSubs.getOrPut(s.parentId to s.name.lowercase()) { s } }
                    drafts += d.copy(newSubcategory = sub)
                    claimed += d.rawIds
                }
            }
        }

        fun verdicts(list: List<com.fasterxml.jackson.databind.JsonNode>, what: String): List<RawVerdict> =
            list.mapNotNull { n ->
                val id = n.longOrNull("raw_id") ?: return@mapNotNull null
                val reason = n.text("reason") ?: what
                when {
                    raws[id] == null -> { rejects += "raw#$id 不存在"; null }
                    id in claimed -> { rejects += "raw#$id 已经记进一笔流水，不能同时$what"; null }
                    owners[id]?.state?.rewritable == false -> { rejects += "raw#$id 属于用户确认过的 #${owners[id]!!.id}，不动"; null }
                    owners[id] != null -> { rejects += "raw#$id 已经在 #${owners[id]!!.id} 里了；要改那笔用别的办法"; null }
                    else -> { claimed += id; RawVerdict(id, reason) }
                }
            }
        val ignored = verdicts(ignoreItems, "忽略")
        val unreadable = verdicts(unreadableItems, "标看不清")

        if (drafts.isEmpty() && ignored.isEmpty() && unreadable.isEmpty()) {
            return ToolOutcome(
                "一笔都没记。" + rejects.joinToString("；") + "。改好了再交一次；实在读不出的放进 unreadable。",
                ok = false
            )
        }

        val ids = backend.record(drafts, ignored, unreadable, Actor.MODEL, parsedBy())
        linkRefundsByRaw(items, drafts, ids)
        val written = backend.txns(ids).associateBy { it.id }
        val out = buildString {
            append("记下 ${ids.size} 笔")
            if (ids.isNotEmpty()) append("：\n").append(ids.mapNotNull { written[it] }.joinToString("\n") { LedgerText.txn(it, zone) })
            if (ignored.isNotEmpty()) append("\n忽略 ${ignored.size} 条：").append(ignored.joinToString("、") { "raw#${it.rawId}" })
            if (unreadable.isNotEmpty()) append("\n看不清 ${unreadable.size} 条：").append(unreadable.joinToString("、") { "raw#${it.rawId}" })
            if (rejects.isNotEmpty()) append("\n没记下（这些通知还在待整理，改好再交）：\n").append(rejects.joinToString("\n"))
        }
        return ToolOutcome(
            out, ok = rejects.isEmpty(),
            payload = Recorded(ids, ignored.size, unreadable.size, rejects.size)
        )
    }

    /**
     * 退款和原笔在同一批里：原笔落库之前没有 # 编号，模型只能指原笔的通知（refund_of_raw）。
     * 这里等整批写完，按通知找到原笔再挂上，类别跟原笔走。
     */
    private suspend fun linkRefundsByRaw(items: List<com.fasterxml.jackson.databind.JsonNode>, drafts: List<ExpenseDraft>, ids: List<Long>) {
        val categories = backend.categories()
        val changes = mutableListOf<TxnChange>()
        drafts.zip(ids).forEachIndexed { i, (d, refundId) ->
            if (d.direction != Direction.REFUND || d.refundOf != null) return@forEachIndexed
            val originRaw = items.getOrNull(i)?.longOrNull("refund_of_raw")
            val origin = originRaw?.let { backend.txnsOfRaws(listOf(it))[it] }
                // 兜底：模型没指，但同一个商户最近只有一笔不小于它的支出 —— 那就是它
                ?: sameMerchantOrigin(d)
            if (origin == null || origin.direction != Direction.OUT || origin.id == refundId) return@forEachIndexed
            changes += TxnChange(
                txnId = refundId, refundOf = origin.id,
                categoryId = categoryIdOfPath(origin.category, categories),
                reason = "退款挂上原笔 #${origin.id}"
            )
        }
        if (changes.isNotEmpty()) backend.update(changes, Actor.MODEL)
    }

    /** 同商户（原样商户串一致）、60 天内、金额不小于退款的支出，恰好一笔才算 */
    private suspend fun sameMerchantOrigin(refund: ExpenseDraft): TxnBrief? {
        val raw = refund.merchantRaw ?: return null
        val from = refund.occurredAt - 60L * 24 * 3600 * 1000
        val candidates = backend.query(ExpenseQuery(direction = Direction.OUT, keyword = raw, limit = 20))
            .filter { it.direction == Direction.OUT && it.merchantRaw == raw && it.amount >= refund.amount && it.occurredAt in from..refund.occurredAt }
        return candidates.singleOrNull()
    }

    private fun draftOf(
        e: com.fasterxml.jackson.databind.JsonNode,
        raws: Map<Long, RawNote>,
        owners: Map<Long, TxnBrief>,
        categories: List<CategoryNode>,
        refundTargets: Map<Long, TxnBrief>,
        claimed: Set<Long>,
        zone: ZoneId
    ): LedgerGuard.Check<ExpenseDraft> {
        val rawIds = e.longs("raw_ids").distinct()
        if (rawIds.isEmpty()) return LedgerGuard.Check.No("raw_ids 是空的，每笔都要引用原始通知")
        val missing = rawIds.filter { raws[it] == null }
        if (missing.isNotEmpty()) return LedgerGuard.Check.No("raw#${missing.joinToString(",raw#")} 不存在")
        val dup = rawIds.filter { it in claimed }
        if (dup.isNotEmpty()) return LedgerGuard.Check.No("raw#${dup.joinToString(",raw#")} 在这一批里已经用过了，一条通知只能属于一笔")
        val sources = rawIds.map { raws.getValue(it) }

        val direction = Direction.entries.firstOrNull { it.name == e.text("direction") }
            ?: return LedgerGuard.Check.No("direction 只能是 ${LedgerJson.DIRECTIONS}")
        val amount = Money.parseCents(e.text("amount"))
            ?: return LedgerGuard.Check.No("金额「${e.text("amount")}」不是正数，照原文抄，如 319.40")
        if (!LedgerGuard.amountInRaw(amount, sources)) {
            return LedgerGuard.Check.No(
                "金额 ${Money.yuan(amount)} 在 raw#${rawIds.joinToString(",raw#")} 的原文里找不到" +
                    if (sources.all { it.redacted }) "（正文被遮蔽了，放进 unreadable）" else "，照原文抄"
            )
        }

        val given = LedgerJson.instant(e.text("occurred_at"), zone)
        val occurredAt = given ?: sources.minOf { it.postTime }
        if (given != null && !LedgerGuard.timeNearRaw(given, sources)) {
            return LedgerGuard.Check.No("发生时刻 ${e.text("occurred_at")} 离通知时刻超过 24 小时，拿不准就填 null")
        }

        // 已经挂在别的流水上：可改的就地更新，确认过的不动
        val existing = rawIds.mapNotNull { owners[it] }.distinctBy { it.id }
        if (existing.any { !it.state.rewritable }) {
            return LedgerGuard.Check.No("这几条通知属于用户确认过的 #${existing.first { !it.state.rewritable }.id}，不动")
        }
        if (existing.size > 1) {
            return LedgerGuard.Check.No("这几条通知现在分属 ${existing.joinToString("、") { "#${it.id}" }} 几笔，不能直接合成一笔")
        }

        val refundOf = e.longOrNull("refund_of")
        val target = refundOf?.let { refundTargets[it] }
        if (refundOf != null) {
            if (direction != Direction.REFUND) return LedgerGuard.Check.No("只有退款（REFUND）才填 refund_of")
            if (target == null || target.state == TxnState.VOID) return LedgerGuard.Check.No("refund_of #$refundOf 不存在，找不到原笔就填 null")
            if (target.direction != Direction.OUT) return LedgerGuard.Check.No("#$refundOf 不是一笔支出，退款挂不上去")
        }

        // 退款挂上了原笔：类别跟原笔走（§10.6 第 11 条），模型给的不看
        val pick = if (target != null) {
            LedgerGuard.Check.Ok(LedgerGuard.CategoryPick(categoryIdOfPath(target.category, categories), null))
        } else {
            LedgerGuard.pickCategory(e.text("category"), direction, categories, allowNewSub = true)
        }
        if (pick is LedgerGuard.Check.No) return pick
        val category = (pick as LedgerGuard.Check.Ok).value

        val ask = e.text("ask")
        val summary = e.text("summary") ?: return LedgerGuard.Check.No("summary 必填")
        return LedgerGuard.Check.Ok(
            ExpenseDraft(
                rawIds = rawIds,
                direction = direction,
                amount = amount,
                occurredAt = occurredAt,
                timeBasis = if (given != null) TimeBasis.EXACT else TimeBasis.NOTIFIED,
                account = e.account(),
                channel = e.text("channel"),
                merchantRaw = e.text("merchant_raw"),
                merchant = e.text("merchant"),
                summary = summary.take(40),
                note = null,
                categoryId = category.categoryId,
                newSubcategory = category.newSub,
                tags = e.strings("tags").take(8),
                confidence = e.get("confidence")?.takeIf { it.isNumber }?.asDouble()?.coerceIn(0.0, 1.0),
                // 没归类又没留问题：替它补一句，不然晚上对账时没话可问
                ask = ask ?: if (category.categoryId == null && category.newSub == null && direction.categoryKind != null) {
                    "${LedgerJson.stamp(occurredAt, zone)} ${direction.label} ${Money.yuan(amount)}，这是什么？"
                } else null,
                refundOf = target?.id,
                replaces = existing.firstOrNull()?.id
            )
        )
    }

    companion object {
        const val NAME = "record_expenses"

        /** 「日用/理发」（流水摘要里的写法）→ 类别 id。拆过几类的取第一段 */
        internal fun categoryIdOfPath(path: String?, categories: List<CategoryNode>): Long? {
            val first = path?.substringBefore('+')?.trim() ?: return null
            val parts = splitCategoryPath(first)
            val top = categories.firstOrNull { it.isTop && it.name == parts.getOrNull(0) } ?: return null
            if (parts.size == 1) return top.id
            return categories.firstOrNull { it.parentId == top.id && it.name == parts[1] }?.id
        }
    }
}
