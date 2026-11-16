package com.abc.daodian.ledger.card

import android.content.Context
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.ledger.LedgerModule
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.ledger.tools.RecordExpensesTool
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first

/**
 * 记账给桌面卡片的：今天 / 这周 / 这个月花了多少（净支出 = 支出 − 退款，口径同 §10.3）、每天各花多少（配柱子）。见 DESIGN.md §8.5
 * 账变了由接头（`LedgerModule.onAppStart`）盯着库喊卡片重画。
 */
object LedgerCards : CardSource {

    override val id = LedgerModule.ID

    override val label = LedgerModule.LABEL

    override val brief = "时段写 today、week（这周一到今天）或 month（这个月 1 号到今天）；类别写「餐饮」或「餐饮/外卖」，照对话最前面的类别表。"

    override val fields = listOf(
        CardField("spent", CardKind.NUMBER, "花了多少元（净支出，四舍五入到元）", "today|week|month [类别]"),
        CardField("count", CardKind.NUMBER, "花了几笔", "today|week|month"),
        CardField("daily", CardKind.SERIES, "每天各花多少（柱子用；week 是周一到周日，month 是这个月每一天）", "week|month")
    )

    override suspend fun read(context: Context, name: String, args: List<String>): CardValue? {
        val (from, to) = range(args.firstOrNull(), LocalDate.now()) ?: return null
        val dao = LedgerStore.get(context).dao
        val f = LedgerDays.dayInt(from)
        val t = LedgerDays.dayInt(to)
        return when (name) {
            "spent" -> {
                val category = args.drop(1).joinToString(" ").trim()
                if (category.isEmpty()) {
                    CardValue.Number(yuan(dao.observeDaily(f, t).first().sumOf { it.amount }))
                } else {
                    val cats = LedgerStore.get(context).categories()
                    val id = RecordExpensesTool.categoryIdOfPath(category, cats) ?: return null
                    val node = cats.firstOrNull { it.id == id } ?: return null
                    val sums = if (node.isTop) dao.observeByTop(f, t, income = false).first() else dao.observeLeaves(f, t, node.parentId ?: id).first()
                    CardValue.Number(yuan(sums.filter { it.key == id }.sumOf { it.amount }))
                }
            }
            "count" -> CardValue.Number(dao.observeTotals(f, t).first().filter { it.direction == "OUT" }.sumOf { it.n }.toLong())
            "daily" -> {
                val today = LocalDate.now()
                val end = if (args.firstOrNull() == "week") from.plusDays(6) else from.with(TemporalAdjusters.lastDayOfMonth())
                val byDay = dao.observeDaily(f, LedgerDays.dayInt(end)).first().associate { it.key to it.amount }
                val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= end }.toList()
                CardValue.Series(
                    values = days.map { yuan(byDay[LedgerDays.dayInt(it).toLong()] ?: 0) },
                    labels = days.map { d -> if (args.firstOrNull() == "week") WEEK[d.dayOfWeek.value - 1] else if (d.dayOfMonth == 1 || d.dayOfMonth % 10 == 0) "${d.dayOfMonth}" else "" },
                    current = days.indexOf(today)
                )
            }
            else -> null
        }
    }

    private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

    /** 分 → 元，四舍五入 */
    private fun yuan(cents: Long): Long = Math.round(cents / 100.0)

    /** 时段的头尾（含）。认不出是 null */
    fun range(period: String?, today: LocalDate): Pair<LocalDate, LocalDate>? = when (period) {
        "today" -> today to today
        "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
        "month" -> today.withDayOfMonth(1) to today
        else -> null
    }
}
