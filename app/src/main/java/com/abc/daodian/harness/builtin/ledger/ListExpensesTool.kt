package com.abc.daodian.harness.builtin.ledger

import com.abc.daodian.harness.builtin.ledger.LedgerJson.longOrNull
import com.abc.daodian.harness.builtin.ledger.LedgerJson.text
import com.abc.daodian.harness.tool.Tool
import com.abc.daodian.harness.tool.ToolContext
import com.abc.daodian.harness.tool.ToolEffect
import com.abc.daodian.harness.tool.ToolOutcome
import java.time.ZoneId

/** 查账：按时间段、类别、方向、状态、关键词、标签筛，给明细和合计。只读，不用问 */
class ListExpensesTool(
    private val backend: LedgerBackend,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : Tool {

    override val name = NAME

    override val effect = ToolEffect.READ

    override val description =
        "查账。用户问「这个月吃饭花了多少」「山姆那笔是哪天」、或者你要找退款对应的原笔、要改某笔之前先查它的 # 编号，都用它。" +
            "返回每笔明细和合计。统计口径：净支出 = 支出 − 退款，转移不算花钱。"

    override val parameters: Map<String, Any?> = LedgerJson.obj(
        "from" to LedgerJson.strOrNull("起始日期 YYYY-MM-DD（含）；不限 null"),
        "to" to LedgerJson.strOrNull("截止日期 YYYY-MM-DD（含）；不限 null"),
        "category" to LedgerJson.strOrNull("类别，「餐饮」连同它的二级一起查，「餐饮/外卖」只查这一个，「未归类」查没归类的；不限 null"),
        "direction" to LedgerJson.enumOrNull("方向；不限 null", LedgerJson.DIRECTIONS),
        "state" to LedgerJson.enumOrNull(
            "AUTO 自动归的 / PENDING 待确认 / CONFIRMED 用户确认过；不限 null",
            listOf("AUTO", "PENDING", "CONFIRMED")
        ),
        "keyword" to LedgerJson.strOrNull("在摘要、备注、商户里找；不限 null"),
        "tag" to LedgerJson.strOrNull("标签；不限 null"),
        "limit" to LedgerJson.intOrNull("最多几笔，默认 50，最多 200")
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = LedgerJson.parse(arguments) ?: return ToolOutcome("参数不是合法的 JSON", ok = false)
        val zone = zone()
        val categoryText = o.text("category")
        val categoryId = when {
            categoryText == null -> null
            categoryText == "未归类" -> UNCATEGORIZED
            else -> {
                val cats = backend.categories()
                RecordExpensesTool.categoryIdOfPath(categoryText, cats)
                    ?: return ToolOutcome("没有「$categoryText」这个类别。${LedgerText.categoryTree(cats)}", ok = false)
            }
        }
        val q = ExpenseQuery(
            fromDay = LedgerJson.dayOf(o.text("from")),
            toDay = LedgerJson.dayOf(o.text("to")),
            categoryId = categoryId,
            direction = Direction.entries.firstOrNull { it.name == o.text("direction") },
            state = TxnState.entries.firstOrNull { it.name == o.text("state") },
            keyword = o.text("keyword"),
            tag = o.text("tag"),
            limit = (o.longOrNull("limit") ?: 50L).toInt().coerceIn(1, 200)
        )
        val list = backend.query(q)
        if (list.isEmpty()) return ToolOutcome("没有符合条件的流水。", ok = true)
        val out = LedgerText.totals(list) + "\n" + list.joinToString("\n") { LedgerText.txn(it, zone) } +
            if (list.size >= q.limit) "\n（只列了前 ${q.limit} 笔，合计也只算了这些；要全的就把时间段缩小或把 limit 调大）" else ""
        return ToolOutcome(out, ok = true)
    }

    companion object {
        const val NAME = "list_expenses"

        /** [ExpenseQuery.categoryId] 用它表示「未归类」 */
        const val UNCATEGORIZED = -1L
    }
}
