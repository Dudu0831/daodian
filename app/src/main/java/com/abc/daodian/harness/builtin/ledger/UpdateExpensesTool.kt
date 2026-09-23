package com.abc.daodian.harness.builtin.ledger

import com.abc.daodian.harness.builtin.ledger.LedgerJson.longOrNull
import com.abc.daodian.harness.builtin.ledger.LedgerJson.objects
import com.abc.daodian.harness.builtin.ledger.LedgerJson.strings
import com.abc.daodian.harness.builtin.ledger.LedgerJson.text
import com.abc.daodian.harness.tool.Tool
import com.abc.daodian.harness.tool.ToolContext
import com.abc.daodian.harness.tool.ToolEffect
import com.abc.daodian.harness.tool.ToolOutcome
import com.fasterxml.jackson.databind.JsonNode
import java.time.ZoneId

/**
 * 在对话里改账：归类、拆成几类、改摘要 / 备注、打标签、挂退款、作废、记住商户。
 * 用户说的就是确认过的 —— 改了类别的那笔一并变成「你确认过」，以后整理重跑也不会再动它。
 *
 * 成功时 [ToolOutcome.payload] 是改过的流水 id 列表。
 */
class UpdateExpensesTool(
    private val backend: LedgerBackend,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : Tool {

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "改已有的流水（按 # 编号）。用户说「197 那笔是理发」「山姆那笔一半是吃的」「这笔不是我花的，删掉」时用。" +
            "不知道编号先用 list_expenses 查。用户明说了类别就 confirm=true；" +
            "他说「以后这家都算 X」才 remember_merchant=true。"

    override val parameters: Map<String, Any?> = LedgerJson.obj(
        "changes" to LedgerJson.arr(
            "每笔要改什么；不改的字段填 null / 空数组 / false",
            LedgerJson.obj(
                "txn_id" to LedgerJson.int("流水 # 后面的数字"),
                "category" to LedgerJson.strOrNull("整笔归到这个类别「一级/二级」；不改 null"),
                "split" to LedgerJson.arrOrNull(
                    "拆成几类，金额加起来必须等于这笔的金额；不拆 null",
                    LedgerJson.obj(
                        "category" to LedgerJson.str("「一级/二级」"),
                        "amount" to LedgerJson.str("这一份多少元")
                    )
                ),
                "summary" to LedgerJson.strOrNull("新的一句话摘要；不改 null"),
                "note" to LedgerJson.strOrNull("备注，用户补充的话；不改 null"),
                "add_tags" to LedgerJson.arr("加上的标签", mapOf("type" to "string")),
                "merchant" to LedgerJson.strOrNull("商户名；不改 null"),
                "remember_merchant" to LedgerJson.bool("以后这个商户默认归这个类别（只有用户这么说了才 true）"),
                "confirm" to LedgerJson.bool("用户确认了这笔（类别对了）"),
                "void_reason" to LedgerJson.strOrNull("作废这笔（不是账、重复了、记错了），写原因；不作废 null"),
                "refund_of" to LedgerJson.intOrNull("这笔退款退的是哪笔 #；不改 null")
            )
        )
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val root = LedgerJson.parse(arguments) ?: return ToolOutcome("参数不是合法的 JSON", ok = false)
        val zone = zone()
        val items = root.objects("changes")
        if (items.isEmpty()) return ToolOutcome("changes 是空的，没改任何东西。", ok = false)

        val categories = backend.categories()
        val txns = backend.txns(items.mapNotNull { it.longOrNull("txn_id") } + items.mapNotNull { it.longOrNull("refund_of") })
            .associateBy { it.id }

        val changes = mutableListOf<TxnChange>()
        val rejects = mutableListOf<String>()
        for (n in items) {
            when (val v = changeOf(n, txns, categories)) {
                is LedgerGuard.Check.Ok -> changes += v.value
                is LedgerGuard.Check.No -> rejects += "#${n.longOrNull("txn_id") ?: "?"}：${v.reason}"
            }
        }
        if (changes.isEmpty()) return ToolOutcome("一笔都没改。" + rejects.joinToString("；"), ok = false)

        backend.update(changes, Actor.USER)
        val after = backend.txns(changes.map { it.txnId })
        // 第一行是给人看的（对话里的回执就画它），下面是给模型的明细
        val headline = after.joinToString("；") { t ->
            "${t.summary} ${Money.yuan(t.amount)}" + if (t.state == TxnState.VOID) " 作废了" else " → ${t.category ?: "未归类"}"
        }
        val out = "改好了 ${after.size} 笔：$headline\n" + after.joinToString("\n") { LedgerText.txn(it, zone) } +
            if (rejects.isNotEmpty()) "\n没改成：\n" + rejects.joinToString("\n") else ""
        return ToolOutcome(out, ok = rejects.isEmpty(), ref = after.firstOrNull()?.id, payload = after.map { it.id })
    }

    private fun changeOf(n: JsonNode, txns: Map<Long, TxnBrief>, categories: List<CategoryNode>): LedgerGuard.Check<TxnChange> {
        val id = n.longOrNull("txn_id") ?: return LedgerGuard.Check.No("缺 txn_id")
        val t = txns[id] ?: return LedgerGuard.Check.No("没有这笔流水，先用 list_expenses 查编号")
        if (t.state == TxnState.VOID) return LedgerGuard.Check.No("这笔已经作废了")

        val voidReason = n.text("void_reason")
        if (voidReason != null) return LedgerGuard.Check.Ok(TxnChange(id, voidReason = voidReason, reason = voidReason))

        var categoryId: Long? = null
        var newSub: NewSubcategory? = null
        n.text("category")?.let { path ->
            when (val p = LedgerGuard.pickCategory(path, t.direction, categories, allowNewSub = true)) {
                is LedgerGuard.Check.No -> return p
                is LedgerGuard.Check.Ok -> { categoryId = p.value.categoryId; newSub = p.value.newSub }
            }
            if (categoryId == null && newSub == null) return LedgerGuard.Check.No("「$path」解析不出类别")
        }

        val splitNodes = n.get("split")?.takeIf { it.isArray && it.size() > 0 }
        val split = splitNodes?.map { part ->
            val cents = Money.parseCents(part.text("amount")) ?: return LedgerGuard.Check.No("拆分金额「${part.text("amount")}」不是正数")
            val pick = LedgerGuard.pickCategory(part.text("category"), t.direction, categories, allowNewSub = false)
            if (pick is LedgerGuard.Check.No) return LedgerGuard.Check.No("拆分：${pick.reason}（拆分只能用已有的类别）")
            (pick as LedgerGuard.Check.Ok).value.categoryId to cents
        }
        if (split != null) {
            if (categoryId != null || newSub != null) return LedgerGuard.Check.No("category 和 split 只能填一个")
            if (split.sumOf { it.second } != t.amount) {
                return LedgerGuard.Check.No("拆分加起来是 ${Money.yuan(split.sumOf { it.second })}，这笔是 ${Money.yuan(t.amount)}，要对得上")
            }
        }

        val refundOf = n.longOrNull("refund_of")
        if (refundOf != null) {
            if (t.direction != Direction.REFUND) return LedgerGuard.Check.No("这笔不是退款，挂不了 refund_of")
            val target = txns[refundOf]
            if (target == null || target.state == TxnState.VOID || target.direction != Direction.OUT) {
                return LedgerGuard.Check.No("#$refundOf 不是一笔有效的支出")
            }
        }

        val merchant = n.text("merchant")
        val remember = n.get("remember_merchant")?.asBoolean(false) == true
        if (remember && (merchant ?: t.merchant) == null) return LedgerGuard.Check.No("这笔没有商户，记不住")
        if (remember && categoryId == null && newSub == null && t.category == null) {
            return LedgerGuard.Check.No("没有类别可记，先给它归类")
        }

        val touchesCategory = categoryId != null || newSub != null || split != null
        return LedgerGuard.Check.Ok(
            TxnChange(
                txnId = id,
                categoryId = categoryId,
                newSubcategory = newSub,
                split = split,
                summary = n.text("summary"),
                note = n.text("note"),
                addTags = n.strings("add_tags").take(8),
                merchant = merchant,
                rememberMerchant = remember,
                confirm = touchesCategory || n.get("confirm")?.asBoolean(false) == true,
                refundOf = refundOf
            )
        )
    }

    companion object {
        const val NAME = "update_expenses"
    }
}
