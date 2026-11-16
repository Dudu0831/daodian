package com.abc.daodian.card.source

import android.content.Context
import com.abc.daodian.card.own.OwnData
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 卡片模块自带的一类：今天几号、星期几、离某天还有几天、某段日子走了几成。不归哪个模块，谁的卡片都能用。见 DESIGN.md §8.5
 * [today] 测试里换掉
 */
class TimeSource(private val today: () -> LocalDate = { LocalDate.now() }) : CardSource {

    override val id = ID
    override val label = "时间"
    override val brief = "日期写 YYYY-MM-DD。"

    override val fields = listOf(
        CardField("date", CardKind.TEXT, "今天几号「11 月 15 日」"),
        CardField("weekday", CardKind.TEXT, "今天星期几「周五」"),
        CardField("days_until", CardKind.NUMBER, "离那天还有几天（过了是 0）", "日期"),
        CardField("days_since", CardKind.NUMBER, "那天之后过了几天", "日期"),
        CardField("progress", CardKind.NUMBER, "从起点到终点走了百分之几（墨条用）", "起点 终点"),
        CardField("year_left", CardKind.NUMBER, "今年还剩几天"),
        CardField("year_progress", CardKind.NUMBER, "今年过了百分之几")
    )

    override suspend fun read(context: Context, name: String, args: List<String>): CardValue? = read(name, args)

    fun read(name: String, args: List<String>): CardValue? {
        val t = today()
        fun day(i: Int): LocalDate? = args.getOrNull(i)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        return when (name) {
            "date" -> CardValue.Text(OwnData.dateText(t, t))
            "weekday" -> CardValue.Text(WEEKDAYS[t.dayOfWeek.value - 1])
            "days_until" -> day(0)?.let { CardValue.Number(ChronoUnit.DAYS.between(t, it).coerceAtLeast(0)) }
            "days_since" -> day(0)?.let { CardValue.Number(ChronoUnit.DAYS.between(it, t).coerceAtLeast(0)) }
            "progress" -> {
                val from = day(0) ?: return null
                val to = day(1) ?: return null
                CardValue.Number(percent(from, to, t))
            }
            "year_left" -> CardValue.Number(ChronoUnit.DAYS.between(t, LocalDate.of(t.year, 12, 31)))
            "year_progress" -> CardValue.Number(percent(LocalDate.of(t.year, 1, 1), LocalDate.of(t.year + 1, 1, 1), t))
            else -> null
        }
    }

    companion object {
        const val ID = "time"
        val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** [from] 到 [to] 之间，[at] 走了百分之几（0 到 100） */
        fun percent(from: LocalDate, to: LocalDate, at: LocalDate): Long {
            val total = ChronoUnit.DAYS.between(from, to).coerceAtLeast(1)
            return (ChronoUnit.DAYS.between(from, at) * 100 / total).coerceIn(0, 100)
        }
    }
}
