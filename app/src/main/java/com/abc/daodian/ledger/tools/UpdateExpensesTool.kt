package com.abc.daodian.ledger.tools

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome
import com.abc.daodian.ledger.domain.Actor
import com.abc.daodian.ledger.domain.CategoryKind
import com.abc.daodian.ledger.domain.CategoryNode
import com.abc.daodian.ledger.domain.Direction
import com.abc.daodian.ledger.domain.LedgerBackend
import com.abc.daodian.ledger.domain.LedgerGuard
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.domain.Money
import com.abc.daodian.ledger.domain.NewSubcategory
import com.abc.daodian.ledger.domain.TxnBrief
import com.abc.daodian.ledger.domain.TxnChange
import com.abc.daodian.ledger.domain.TxnState
import com.abc.daodian.ledger.tools.LedgerJson.longOrNull
import com.abc.daodian.ledger.tools.LedgerJson.objects
import com.abc.daodian.ledger.tools.LedgerJson.strings
import com.abc.daodian.ledger.tools.LedgerJson.text
import com.fasterxml.jackson.databind.JsonNode
import java.time.ZoneId

/**
 * 在对话里改账：归类、拆成几类、改摘要 / 备注、打标签、改方向、挂退款、作废、记住商户。
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
        "改已有的流水（按 # 编号），一次可以改好几笔。用户说「197 那笔是理发」「山姆那笔一半是吃的」「这笔重复了」" +
            "「根本没这笔钱」「今天这几笔都是约会」「那笔收入其实是充电退回来的」时用。不知道编号先用 list_expenses 查。" +
            "用户明说了类别就 confirm=true；他说「以后这家都算 X」才 remember_merchant=true。"

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
                "add_tags" to LedgerJson.arr("加上的标签，用已有的名字；只在用户说了才打", mapOf("type" to "string")),
                "remove_tags" to LedgerJson.arr("取下的标签（用户说「那笔不算约会」）", mapOf("type" to "string")),
                "merchant" to LedgerJson.strOrNull("商户名；不改 null"),
                "remember_merchant" to LedgerJson.bool("以后这个商户默认归这个类别（只有用户这么说了才 true）"),
                "confirm" to LedgerJson.bool("用户确认了这笔（类别对了）"),
                "void_reason" to LedgerJson.strOrNull("作废这笔（重复了、根本没这笔钱、记错了），写原因；转账给别人是支出，不作废。不作废 null"),
                "direction" to LedgerJson.enumOrNull(
                    "方向记错了才改：储蓄卡的退款通知写的是「收入」，他说那是退款 → REFUND（同时填 refund_of）；" +
                        "还信用卡、自己卡互转被记成支出 → TRANSFER。不改 null",
                    LedgerJson.DIRECTIONS
                ),
                "refund_of" to LedgerJson.intOrNull("这笔退款退的是哪笔 #（类别跟着那笔走，不用填 category）；不改 null")
            )
        )
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val root = LedgerJson.parse(arguments) ?: return ToolOutcome("参数不是合法的 JSON", ok = false)
        val zone = zone()
        val items = root.objects("changes")
        if (items.isEmpty()) return ToolOutcome("changes 是空的，没改任何东西。", ok = false)

        val categories = backend.categories()
        val tagNames = backend.tagNames()
        val txns = backend.txns(items.mapNotNull { it.longOrNull("txn_id") } + items.mapNotNull { it.longOrNull("refund_of") })
            .associateBy { it.id }

        val changes = mutableListOf<TxnChange>()
        val rejects = mutableListOf<String>()
        for (n in items) {
            when (val v = changeOf(n, txns, categories, tagNames)) {
                is LedgerGuard.Check.Ok -> changes += v.value
                is LedgerGuard.Check.No -> rejects += "#${n.longOrNull("txn_id") ?: "?"}：${v.reason}"
            }
        }
        if (changes.isEmpty()) return ToolOutcome("一笔都没改。" + rejects.joinToString("；"), ok = false)

        backend.update(changes, Actor.USER)
        val after = backend.txns(changes.map { it.txnId })
        // 第一行是给人看的（对话里的回执就画它），下面是给模型的明细
        val byId = changes.associateBy { it.txnId }
        val headline = after.joinToString("；") { t ->
            val c = byId[t.id]
            val tags = c?.let(::tagDelta).orEmpty()
            val recategorized = c != null && (c.categoryId != null || c.newSubcategory != null || c.split != null)
            "${t.summary} ${Money.yuan(t.amount)}" + when {
                t.state == TxnState.VOID -> " 作废了"
                // 「充电退款 1.82 改成退款 → 交通」；转移不挂类别
                c?.direction != null -> " 改成${t.direction.label}" +
                    if (t.direction.categoryKind == null) "" else " → ${t.category ?: "未归类"}"
                // 只动了标签的，不写类别：「和她吃饭 356.00 · 标签 +约会」
                recategorized || tags.isEmpty() -> " → ${t.category ?: "未归类"}"
                else -> ""
            } + tags
        }
        val out = "改好了 ${after.size} 笔：$headline\n" + after.joinToString("\n") { LedgerText.txn(it, zone) } +
            if (rejects.isNotEmpty()) "\n没改成：\n" + rejects.joinToString("\n") else ""
        return ToolOutcome(out, ok = rejects.isEmpty(), ref = after.firstOrNull()?.id, payload = after.map { it.id })
    }

    private fun changeOf(
        n: JsonNode,
        txns: Map<Long, TxnBrief>,
        categories: List<CategoryNode>,
        tagNames: List<String>
    ): LedgerGuard.Check<TxnChange> {
        val id = n.longOrNull("txn_id") ?: return LedgerGuard.Check.No("缺 txn_id")
        val t = txns[id] ?: return LedgerGuard.Check.No("没有这笔流水，先用 list_expenses 查编号")
        if (t.state == TxnState.VOID) return LedgerGuard.Check.No("这笔已经作废了")

        val voidReason = n.text("void_reason")
        if (voidReason != null) return LedgerGuard.Check.Ok(TxnChange(id, voidReason = voidReason, reason = voidReason))

        // 改方向：之后的类别、拆分、退款都按改完的方向查
        val direction = n.text("direction")?.let { d ->
            Direction.entries.firstOrNull { it.name == d } ?: return LedgerGuard.Check.No("direction 只能是 ${LedgerJson.DIRECTIONS}")
        }?.takeIf { it != t.direction }
        val dir = direction ?: t.direction

        val refundOf = n.longOrNull("refund_of")
        val origin = refundOf?.let { txns[it] }
        if (refundOf != null) {
            if (dir != Direction.REFUND) {
                return LedgerGuard.Check.No("这笔是${dir.label}，挂不了 refund_of；银行把退款写成了收入的话，direction 一起改成 REFUND")
            }
            if (refundOf == id) return LedgerGuard.Check.No("退款不能挂在自己身上")
            if (origin == null || origin.state == TxnState.VOID || origin.direction != Direction.OUT) {
                return LedgerGuard.Check.No("#$refundOf 不是一笔有效的支出")
            }
        }

        var categoryId: Long? = null
        var newSub: NewSubcategory? = null
        if (origin != null) {
            // 退款挂上原笔：类别跟原笔走（§10.6 第 11 条），模型给的不看
            categoryId = RecordExpensesTool.categoryIdOfPath(origin.category, categories)
        } else n.text("category")?.takeIf { dir.categoryKind != null }?.let { path ->
            when (val p = LedgerGuard.pickCategory(path, dir, categories, allowNewSub = true, tagNames = tagNames)) {
                is LedgerGuard.Check.No -> return p
                is LedgerGuard.Check.Ok -> { categoryId = p.value.categoryId; newSub = p.value.newSub }
            }
            if (categoryId == null && newSub == null) return LedgerGuard.Check.No("「$path」解析不出类别")
        }

        LedgerGuard.tagClash(n.strings("add_tags"), tagNames, categories)?.let { return LedgerGuard.Check.No(it) }
        val removeTags = n.strings("remove_tags").take(8)
        val attached = t.tags.map { it.lowercase() }
        removeTags.firstOrNull { it.trim().removePrefix("#").lowercase() !in attached }?.let {
            return LedgerGuard.Check.No("这笔没挂「$it」，挂着的是：${t.tags.joinToString("、").ifEmpty { "没有标签" }}")
        }

        val splitNodes = n.get("split")?.takeIf { it.isArray && it.size() > 0 }
        if (splitNodes != null && origin != null) return LedgerGuard.Check.No("挂上原笔的退款类别跟原笔走，不拆，split 填 null")
        if (splitNodes != null && dir.categoryKind == null) return LedgerGuard.Check.No("转移不挂类别，拆不了，split 填 null")
        val split = splitNodes?.map { part ->
            val cents = Money.parseCents(part.text("amount")) ?: return LedgerGuard.Check.No("拆分金额「${part.text("amount")}」不是正数")
            val pick = LedgerGuard.pickCategory(part.text("category"), dir, categories, allowNewSub = false)
            if (pick is LedgerGuard.Check.No) return LedgerGuard.Check.No("拆分：${pick.reason}（拆分只能用已有的类别）")
            (pick as LedgerGuard.Check.Ok).value.categoryId to cents
        }
        if (split != null) {
            if (categoryId != null || newSub != null) return LedgerGuard.Check.No("category 和 split 只能填一个")
            if (split.sumOf { it.second } != t.amount) {
                return LedgerGuard.Check.No("拆分加起来是 ${Money.yuan(split.sumOf { it.second })}，这笔是 ${Money.yuan(t.amount)}，要对得上")
            }
        }

        // 换到了另一边（收入 → 退款、支出 → 收入），原来挂的类别对不上了：一起给个新的，退款就挂原笔
        val kind = dir.categoryKind
        if (direction != null && kind != null && kind != t.direction.categoryKind &&
            refundOf == null && categoryId == null && newSub == null && split == null
        ) {
            return LedgerGuard.Check.No(
                "改成${dir.label}后原来的类别对不上了：" +
                    if (dir == Direction.REFUND) "找到退的是哪笔填 refund_of（类别跟原笔走），找不到就给个支出类别"
                    else "一起给个${if (kind == CategoryKind.OUT) "支出" else "收入"}类别"
            )
        }

        val merchant = n.text("merchant")
        val remember = n.get("remember_merchant")?.asBoolean(false) == true
        if (remember && (merchant ?: t.merchant) == null) return LedgerGuard.Check.No("这笔没有商户，记不住")
        if (remember && categoryId == null && newSub == null && t.category == null) {
            return LedgerGuard.Check.No("没有类别可记，先给它归类")
        }

        val touchesCategory = categoryId != null || newSub != null || split != null || direction != null
        return LedgerGuard.Check.Ok(
            TxnChange(
                txnId = id,
                categoryId = categoryId,
                newSubcategory = newSub,
                split = split,
                summary = n.text("summary"),
                note = n.text("note"),
                addTags = n.strings("add_tags").take(8),
                removeTags = removeTags,
                merchant = merchant,
                rememberMerchant = remember,
                confirm = touchesCategory || n.get("confirm")?.asBoolean(false) == true,
                direction = direction,
                refundOf = refundOf
            )
        )
    }

    /** 「 · 标签 +约会 −请客」；没动标签是空的 */
    private fun tagDelta(c: TxnChange): String {
        val parts = c.addTags.map { "+" + it.trim().removePrefix("#") } + c.removeTags.map { "−" + it.trim().removePrefix("#") }
        return if (parts.isEmpty()) "" else " · 标签 " + parts.joinToString(" ")
    }

    companion object {
        const val NAME = "update_expenses"
    }
}
