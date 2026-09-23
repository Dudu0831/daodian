package com.abc.daodian.ui.ledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.harness.background.AgentActivity
import com.abc.daodian.harness.builtin.ledger.CategoryKind
import com.abc.daodian.harness.builtin.ledger.Direction
import com.abc.daodian.harness.builtin.ledger.ExpenseQuery
import com.abc.daodian.harness.builtin.ledger.ListExpensesTool
import com.abc.daodian.harness.builtin.ledger.RawNote
import com.abc.daodian.harness.builtin.ledger.TxnBrief
import com.abc.daodian.ledger.LedgerSettings
import com.abc.daodian.ledger.LedgerStore
import com.abc.daodian.ledger.check.LedgerCheck
import com.abc.daodian.ledger.db.Category
import com.abc.daodian.ledger.db.ChangeLog
import com.abc.daodian.ledger.db.DirectionSum
import com.abc.daodian.ledger.db.Sum
import com.abc.daodian.ledger.organize.OrganizeWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** 看账的粒度 */
enum class PeriodMode(val label: String) { DAY("日"), MONTH("月"), QUARTER("季"), YEAR("年") }

/** 一段日子：粒度 + 落在哪一天。[from] / [to] 是 yyyyMMdd，和 txn.day 同一种写法 */
data class Period(val mode: PeriodMode, val anchor: LocalDate) {

    val start: LocalDate
        get() = when (mode) {
            PeriodMode.DAY -> anchor
            PeriodMode.MONTH -> anchor.withDayOfMonth(1)
            PeriodMode.QUARTER -> LocalDate.of(anchor.year, (anchor.monthValue - 1) / 3 * 3 + 1, 1)
            PeriodMode.YEAR -> LocalDate.of(anchor.year, 1, 1)
        }

    val end: LocalDate
        get() = when (mode) {
            PeriodMode.DAY -> anchor
            PeriodMode.MONTH -> YearMonth.from(anchor).atEndOfMonth()
            PeriodMode.QUARTER -> YearMonth.from(start.plusMonths(2)).atEndOfMonth()
            PeriodMode.YEAR -> LocalDate.of(anchor.year, 12, 31)
        }

    val from: Int get() = dayInt(start)
    val to: Int get() = dayInt(end)

    fun shift(n: Long): Period = copy(
        anchor = when (mode) {
            PeriodMode.DAY -> anchor.plusDays(n)
            PeriodMode.MONTH -> anchor.plusMonths(n)
            PeriodMode.QUARTER -> anchor.plusMonths(3 * n)
            PeriodMode.YEAR -> anchor.plusYears(n)
        }
    )

    /** 「2026年 9月」「9月23日 周三」 */
    val label: String
        get() = when (mode) {
            PeriodMode.DAY -> "${anchor.monthValue}月${anchor.dayOfMonth}日 ${WEEK[anchor.dayOfWeek.value - 1]}"
            PeriodMode.MONTH -> "${anchor.year}年 ${anchor.monthValue}月"
            PeriodMode.QUARTER -> "${anchor.year}年 ${QUARTERS[(anchor.monthValue - 1) / 3]}季度"
            PeriodMode.YEAR -> "${anchor.year}年"
        }

    /** 大数字上面那行小字：「九月 · 支出」 */
    val kicker: String
        get() = when (mode) {
            PeriodMode.DAY -> if (anchor == LocalDate.now()) "今天" else "这天"
            PeriodMode.MONTH -> "${MONTHS[anchor.monthValue - 1]}月"
            PeriodMode.QUARTER -> "${QUARTERS[(anchor.monthValue - 1) / 3]}季度"
            PeriodMode.YEAR -> if (anchor.year == LocalDate.now().year) "今年" else "${anchor.year}年"
        }

    /** 翻到了将来：往后那个箭头就灰掉 */
    val isCurrentOrLater: Boolean get() = !end.isBefore(LocalDate.now())

    companion object {
        private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        private val MONTHS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
        private val QUARTERS = arrayOf("一", "二", "三", "四")

        fun dayInt(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
        fun dateOf(day: Int): LocalDate = LocalDate.of(day / 10000, day / 100 % 100, day % 100)
    }
}

/** 总览里一个类别一行。[topId] = null 是「未归类」 */
data class CategorySlice(val topId: Long?, val name: String, val amount: Long, val count: Int)

/** 趋势里一根柱子。[period] 是点它要进到的那一段；null = 点不进去（还没开始记、还没到） */
data class Bar(val label: String, val amount: Long, val period: Period?, val beforeData: Boolean, val future: Boolean)

data class Overview(
    val period: Period,
    val spent: Long,
    val income: Long,
    val count: Int,
    val bars: List<Bar>,
    val spending: List<CategorySlice>,
    val incomes: List<CategorySlice>,
    val pending: Int,
    /** 最早一笔账是哪天（yyyyMMdd）；没有账是 null */
    val firstDay: Int?
)

data class CategoryDetail(
    val name: String,
    val kind: CategoryKind,
    val total: Long,
    /** 占这段支出（或收入）的比例，0~1 */
    val share: Double,
    val leaves: List<CategorySlice>,
    val txns: List<TxnBrief>
)

data class TxnDetail(val txn: TxnBrief, val raws: List<RawNote>, val changes: List<ChangeLog>, val categories: List<Category>)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LedgerStore.get(app)
    private val dao = store.dao

    val period = MutableStateFlow(Period(PeriodMode.MONTH, LocalDate.now()))

    fun setPeriod(p: Period) {
        period.value = p
    }

    val overview: StateFlow<Overview?> = period.flatMapLatest(::overviewOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 抽屉里那张纸只看这个月，不跟着总览页翻 */
    val thisMonth: StateFlow<Overview?> = overviewOf(Period(PeriodMode.MONTH, LocalDate.now()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun overviewOf(p: Period): Flow<Overview> {
        val totals = dao.observeTotals(p.from, p.to)
        val tops = combine(dao.observeByTop(p.from, p.to, false), dao.observeByTop(p.from, p.to, true), ::Pair)
        val meta = combine(dao.observeCategories(), dao.observePendingTxns(), dao.observeFirstDay(), ::Triple)
        return combine(totals, tops, dao.observeDaily(p.from, p.to), meta) { t, (out, inc), daily, (cats, pending, first) ->
            fun sum(d: Direction) = t.firstOrNull { it.direction == d.name }?.amount ?: 0L
            Overview(
                period = p,
                spent = sum(Direction.OUT) - sum(Direction.REFUND),
                income = sum(Direction.IN),
                count = t.sumOf(DirectionSum::n),
                bars = barsOf(p, daily, first),
                spending = slices(out, cats),
                incomes = slices(inc, cats),
                pending = pending,
                firstDay = first
            )
        }
    }

    private fun slices(sums: List<Sum>, cats: List<Category>): List<CategorySlice> {
        val byId = cats.associateBy { it.id }
        return sums.map { s -> CategorySlice(s.key, s.key?.let { byId[it]?.name } ?: "未归类", s.amount, s.n) }
            // 未归类放最后，其余按钱多少排
            .sortedWith(compareBy<CategorySlice> { it.topId == null }.thenByDescending { it.amount })
    }

    /** 月 → 每天一根；季、年 → 每月一根；日 → 没有柱子 */
    private fun barsOf(p: Period, daily: List<Sum>, firstDay: Int?): List<Bar> {
        val today = LocalDate.now()
        val first = firstDay?.let(Period::dateOf)
        val perDay = daily.associate { (it.key ?: 0L).toInt() to it.amount }
        return when (p.mode) {
            PeriodMode.DAY -> emptyList()
            PeriodMode.MONTH -> generateSequence(p.start) { it.plusDays(1) }.takeWhile { !it.isAfter(p.end) }.map { d ->
                val amount = perDay[Period.dayInt(d)] ?: 0L
                Bar(
                    label = if (d.dayOfMonth in setOf(1, 10, 20) || d == p.end) "${d.dayOfMonth}日" else "",
                    amount = amount,
                    period = Period(PeriodMode.DAY, d).takeIf { !d.isAfter(today) && (first == null || !d.isBefore(first)) },
                    beforeData = first == null || d.isBefore(first),
                    future = d.isAfter(today)
                )
            }.toList()
            PeriodMode.QUARTER, PeriodMode.YEAR -> generateSequence(YearMonth.from(p.start)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(p.end)) }.map { m ->
                    val amount = perDay.filterKeys { it / 100 == m.year * 100 + m.monthValue }.values.sum()
                    val start = m.atDay(1)
                    Bar(
                        label = "${m.monthValue}月",
                        amount = amount,
                        period = Period(PeriodMode.MONTH, start).takeIf { !start.isAfter(today) },
                        beforeData = first == null || m.atEndOfMonth().isBefore(first),
                        future = start.isAfter(today)
                    )
                }.toList()
        }
    }

    // ---------------- 第二层：一个类别 ----------------

    /** [topId] 是一级类别 id；[ListExpensesTool.UNCATEGORIZED] 是「未归类」 */
    fun category(topId: Long, income: Boolean, p: Period): Flow<CategoryDetail> {
        val change = dao.observeLastChange()
        return combine(change, dao.observeCategories(), dao.observeByTop(p.from, p.to, income)) { _, cats, tops -> cats to tops }
            .mapLatest { (cats, tops) ->
                val byId = cats.associateBy { it.id }
                val top = byId[topId]
                val total = tops.firstOrNull { (it.key ?: ListExpensesTool.UNCATEGORIZED) == topId }?.amount ?: 0L
                val all = tops.sumOf { it.amount }.takeIf { it != 0L }
                val txns = store.query(
                    ExpenseQuery(
                        fromDay = p.from, toDay = p.to, categoryId = topId, limit = 500,
                        direction = null
                    )
                ).filter { t -> if (income) t.direction == Direction.IN else t.direction == Direction.OUT || t.direction == Direction.REFUND }
                val leaves = if (top == null) emptyList() else {
                    txnsLeaves(txns, cats, topId)
                }
                CategoryDetail(
                    name = top?.name ?: "未归类",
                    kind = top?.kind ?: CategoryKind.OUT,
                    total = total,
                    share = if (all == null) 0.0 else total.toDouble() / all,
                    leaves = leaves,
                    txns = txns
                )
            }
    }

    /** 按二级分：从这一类的流水里数（拆过的只算落在这一类里的那份）。没二级的一级就是它自己一行 */
    private suspend fun txnsLeaves(txns: List<TxnBrief>, cats: List<Category>, topId: Long): List<CategorySlice> {
        val allocs = dao.allocations(txns.map { it.id })
        val sign = txns.associate { it.id to if (it.direction == Direction.REFUND) -1 else 1 }
        val inTop = cats.filter { it.id == topId || it.parentId == topId }.associateBy { it.id }
        return allocs.filter { it.categoryId in inTop }
            .groupBy { it.categoryId!! }
            .map { (cid, list) ->
                CategorySlice(cid, inTop.getValue(cid).name, list.sumOf { it.amount * (sign[it.txnId] ?: 1) }, list.map { it.txnId }.distinct().size)
            }
            .sortedByDescending { it.amount }
    }

    // ---------------- 第三层：一笔 ----------------

    fun txn(id: Long): Flow<TxnDetail?> = combine(dao.observeLastChange(), dao.observeCategories()) { _, cats -> cats }
        .mapLatest { cats ->
            val t = store.txns(listOf(id)).firstOrNull() ?: return@mapLatest null
            TxnDetail(t, store.raws(t.rawIds).sortedBy { it.postTime }, dao.changesOf(id), cats)
        }

    // ---------------- 设置 ----------------

    val organizeHours = LedgerSettings.organizeHoursFlow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LedgerSettings.DEFAULT_ORGANIZE_HOURS)

    val checkTime = LedgerSettings.checkTimeFlow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LedgerSettings.DEFAULT_CHECK)

    val lastRun = dao.observeLastRun().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pendingRaws = dao.observePendingRaws().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 后台正在跑的 agent（顶栏印章、设置页都看它） */
    val running = AgentActivity.running

    val organizing: StateFlow<Boolean> = AgentActivity.running.map { list -> list.any { it.id == "organize" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setOrganizeHours(hours: Int) = viewModelScope.launch {
        LedgerSettings.setOrganizeHours(getApplication(), hours)
        OrganizeWorker.schedule(getApplication())
    }

    fun setCheckTime(time: LocalTime) = viewModelScope.launch {
        LedgerSettings.setCheckTime(getApplication(), time)
        LedgerCheck.arm(getApplication())
    }

    fun organizeNow() = OrganizeWorker.runNow(getApplication())
}
