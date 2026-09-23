package com.abc.daodian.ledger.tools

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome
import com.abc.daodian.ledger.domain.Actor
import com.abc.daodian.ledger.domain.Direction
import com.abc.daodian.ledger.domain.ExpenseDraft
import com.abc.daodian.ledger.domain.LedgerBackend
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.ledger.domain.LedgerGuard
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.domain.Money
import com.abc.daodian.ledger.domain.TimeBasis
import com.abc.daodian.ledger.tools.LedgerJson.account
import com.abc.daodian.ledger.tools.LedgerJson.longs
import com.abc.daodian.ledger.tools.LedgerJson.strings
import com.abc.daodian.ledger.tools.LedgerJson.text
import java.time.ZoneId

/**
 * 用户在对话里随口记一笔（「刚才停车 5 块」）。没有原始通知可对，所以不走原文护栏，
 * 靠授权条让他点头；落库即「你确认过」（§10.6 第 10 条）。
 *
 * 成功时 [ToolOutcome.payload] 是新流水的 id。
 */
class AddExpenseTool(
    private val backend: LedgerBackend,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : Tool {

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "用户亲口说了一笔账（现金、没通知的、补记以前的）时记下来。通知里已经有的账不要用它重记 —— " +
            "先用 list_expenses 看看是不是已经有了。"

    override val parameters: Map<String, Any?> = LedgerJson.obj(
        "direction" to LedgerJson.enumOf("OUT 支出 / IN 收入 / REFUND 退款 / TRANSFER 转移", LedgerJson.DIRECTIONS),
        "amount" to LedgerJson.str("金额，元，如「5」「32.50」"),
        "occurred_at" to LedgerJson.strOrNull("发生时刻 ISO-8601 带偏移；说了「刚才」就是他这句话的时刻；只说了哪天填那天 12:00 并把 day_only 设 true"),
        "day_only" to LedgerJson.bool("只知道哪天、不知道几点"),
        "summary" to LedgerJson.str("一句人话，12 字以内"),
        "category" to LedgerJson.strOrNull("类别「一级/二级」；拿不准 null"),
        "account" to LedgerJson.ACCOUNT,
        "channel" to LedgerJson.strOrNull("支付渠道；不知道 null"),
        "merchant" to LedgerJson.strOrNull("商户；没有 null"),
        "tags" to LedgerJson.arr("标签，没有就空数组", mapOf("type" to "string")),
        "note" to LedgerJson.strOrNull("他补充的话；没有 null"),
        "raw_ids" to LedgerJson.arr(
            "只有对账时补记「读不出金额」的那几条通知才填（raw#后面的数字），平时空数组",
            mapOf("type" to "integer")
        )
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = LedgerJson.parse(arguments) ?: return ToolOutcome("参数不是合法的 JSON", ok = false)
        val zone = zone()
        val direction = Direction.entries.firstOrNull { it.name == o.text("direction") }
            ?: return ToolOutcome("没记。direction 只能是 ${LedgerJson.DIRECTIONS}", ok = false)
        val amount = Money.parseCents(o.text("amount"))
            ?: return ToolOutcome("没记。金额「${o.text("amount")}」不是正数", ok = false)
        val nowMillis = context.now.toInstant().toEpochMilli()
        val occurredAt = LedgerDays.instant(o.text("occurred_at"), zone) ?: nowMillis
        if (occurredAt > nowMillis + 60_000) return ToolOutcome("没记。发生时刻在将来，问问他是哪天", ok = false)

        val pick = when (val p = LedgerGuard.pickCategory(o.text("category"), direction, backend.categories(), allowNewSub = true)) {
            is LedgerGuard.Check.No -> return ToolOutcome("没记。${p.reason}", ok = false)
            is LedgerGuard.Check.Ok -> p.value
        }
        val summary = o.text("summary") ?: return ToolOutcome("没记。summary 必填", ok = false)
        // 对账时补记被遮蔽的通知：那几条挂到这笔上，就不会每晚再问一遍
        val rawIds = o.longs("raw_ids").distinct()
        if (rawIds.isNotEmpty()) {
            val raws = backend.raws(rawIds)
            val bad = rawIds.filter { id -> raws.none { it.id == id && it.unreadable } }
            if (bad.isNotEmpty()) return ToolOutcome("没记。raw#${bad.joinToString(",raw#")} 不是读不出金额的通知，raw_ids 留空", ok = false)
        }
        val draft = ExpenseDraft(
            rawIds = rawIds,
            direction = direction,
            amount = amount,
            occurredAt = occurredAt,
            timeBasis = when {
                o.get("day_only")?.asBoolean(false) == true -> TimeBasis.DAY
                o.text("occurred_at") != null -> TimeBasis.EXACT
                else -> TimeBasis.NOTIFIED
            },
            account = o.account(),
            channel = o.text("channel"),
            merchantRaw = null,
            merchant = o.text("merchant"),
            summary = summary.take(40),
            note = o.text("note") ?: context.userInput.take(200),
            categoryId = pick.categoryId,
            newSubcategory = pick.newSub,
            tags = o.strings("tags").take(8),
            confidence = null,
            ask = null,
            refundOf = null,
            replaces = null,
            confirmed = true
        )
        val id = backend.record(listOf(draft), emptyList(), emptyList(), Actor.USER, "chat").single()
        val t = backend.txns(listOf(id)).single()
        return ToolOutcome(
            "记下了：${t.summary} ${Money.signed(t.direction, t.amount)}（${t.category ?: "未归类"}）\n${LedgerText.txn(t, zone)}",
            ok = true, ref = id, payload = id
        )
    }

    companion object {
        const val NAME = "add_expense"
    }
}
