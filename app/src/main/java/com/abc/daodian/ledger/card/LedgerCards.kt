package com.abc.daodian.ledger.card

import android.content.Context
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardRow
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.ledger.LedgerModule
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.ledger.domain.TxnBrief
import com.abc.daodian.ledger.tools.RecordExpensesTool
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first

/**
 * 记账给桌面卡片的：一段日子里花了多少（净支出 = 支出 − 退款，口径同 §10.3）、几笔、每天各花多少（配柱子）、
 * 各类别花了多少、最大的几笔、最近一笔、平均每天、还等你对的几笔。见 DESIGN.md §8.5
 * 账变了由接头（`LedgerModule.onAppStart`）盯着库喊卡片重画。
 */
object LedgerCards : CardSource {

    override val id = LedgerModule.ID

    override val label = LedgerModule.LABEL

    override val brief = "范围写 today、week（这周一到今天）、month（这个月 1 号到今天）、last_week（上周一到周日）、last_month（上个月）；" +
        "类别写「餐饮」或「餐饮/外卖」，照对话最前面的类别表。钱都是元，四舍五入；要写 ¥ 就放在 number 的 before 里。"

    private const val RANGE = "today|week|month|last_week|last_month"

    override val fields = listOf(
        CardField("spent", CardKind.NUMBER, "花了多少元（净支出）", "$RANGE [类别]"),
        CardField("count", CardKind.NUMBER, "花了几笔", RANGE),
        CardField("per_day", CardKind.NUMBER, "平均每天花多少元（这周 / 这个月按到今天的天数算）", RANGE),
        CardField("daily", CardKind.SERIES, "每天各花多少（柱子用；周的是周一到周日，月的是那个月每一天）", RANGE),
        CardField("by_category", CardKind.ROWS, "各类别花了多少，多的在前「餐饮 ¥2,036」；每行的 value 是元数，画比例用", RANGE),
        CardField("biggest", CardKind.ROWS, "最大的几笔「山姆 ¥368」；每行的 value 是元数", RANGE),
        CardField("last", CardKind.TEXT, "最近一笔「瑞幸 ¥18 · 10:42」"),
        CardField("pending", CardKind.NUMBER, "还等你对的几笔")
    )

    override suspend fun read(context: Context, name: String, args: List<String>): CardValue? {
        val store = LedgerStore.get(context)
        val dao = store.dao
        val today = LocalDate.now()
        when (name) {
            "pending" -> return CardValue.Number(dao.observePendingTxns().first().toLong())
            "last" -> {
                val t = dao.lastOut()?.let { store.briefs(listOf(it)).firstOrNull() } ?: return CardValue.Text("")
                return CardValue.Text("${nameOf(t)} ${money(t.amount)} · ${whenOf(t.occurredAt, today)}")
            }
        }
        val period = args.firstOrNull()
        val (from, to) = range(period, today) ?: return null
        val f = LedgerDays.dayInt(from)
        val t = LedgerDays.dayInt(to)
        return when (name) {
            "spent" -> {
                val category = args.drop(1).joinToString(" ").trim()
                if (category.isEmpty()) {
                    CardValue.Number(yuan(dao.observeDaily(f, t).first().sumOf { it.amount }))
                } else {
                    val cats = store.categories()
                    val id = RecordExpensesTool.categoryIdOfPath(category, cats) ?: return null
                    val node = cats.firstOrNull { it.id == id } ?: return null
                    val sums = if (node.isTop) dao.observeByTop(f, t, income = false).first() else dao.observeLeaves(f, t, node.parentId ?: id).first()
                    CardValue.Number(yuan(sums.filter { it.key == id }.sumOf { it.amount }))
                }
            }
            "count" -> CardValue.Number(dao.observeTotals(f, t).first().filter { it.direction == "OUT" }.sumOf { it.n }.toLong())
            "per_day" -> {
                val days = ChronoUnit.DAYS.between(from, to) + 1
                CardValue.Number(yuan(dao.observeDaily(f, t).first().sumOf { it.amount }) / days)
            }
            "daily" -> {
                val week = period?.endsWith("week") == true
                val end = if (week) from.plusDays(6) else from.with(TemporalAdjusters.lastDayOfMonth())
                val byDay = dao.observeDaily(f, LedgerDays.dayInt(end)).first().associate { it.key to it.amount }
                val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= end }.toList()
                CardValue.Series(
                    values = days.map { yuan(byDay[LedgerDays.dayInt(it).toLong()] ?: 0) },
                    labels = days.map { d -> if (week) WEEK[d.dayOfWeek.value - 1] else if (d.dayOfMonth == 1 || d.dayOfMonth % 10 == 0) "${d.dayOfMonth}" else "" },
                    current = days.indexOf(today)
                )
            }
            "by_category" -> {
                val names = store.categories().associate { it.id to it.name }
                val rows = dao.observeByTop(f, t, income = false).first()
                    .filter { it.amount > 0 }
                    .sortedByDescending { it.amount }
                    .map { CardRow(it.key?.let(names::get) ?: "未归类", money(it.amount), value = yuan(it.amount)) }
                CardValue.Rows(rows)
            }
            "biggest" -> CardValue.Rows(store.briefs(dao.biggestOut(f, t, 8)).map { CardRow(nameOf(it), money(it.amount), value = yuan(it.amount)) })
            else -> null
        }
    }

    private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

    /** 分 → 元，四舍五入 */
    private fun yuan(cents: Long): Long = Math.round(cents / 100.0)

    /** 「¥2,036」 */
    private fun money(cents: Long): String = "¥" + "%,d".format(yuan(cents))

    /** 一笔叫什么：商户，没有就用原文里的商户、摘要 */
    private fun nameOf(t: TxnBrief): String = t.merchant ?: t.merchantRaw ?: t.summary

    /** 今天的写几点，别的写哪天 */
    private fun whenOf(at: Long, today: LocalDate): String {
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return if (t.toLocalDate() == today) "%02d:%02d".format(t.hour, t.minute) else "${t.monthValue}月${t.dayOfMonth}日"
    }

    /** 范围的头尾（含）。认不出是 null */
    fun range(period: String?, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val first = today.withDayOfMonth(1)
        return when (period) {
            "today" -> today to today
            "week" -> monday to today
            "month" -> first to today
            "last_week" -> monday.minusDays(7) to monday.minusDays(1)
            "last_month" -> first.minusMonths(1) to first.minusDays(1)
            else -> null
        }
    }
}
