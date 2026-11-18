package com.abc.daodian.card.source

import android.content.Context
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.spec.CardSpecs
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 卡片模块自带的一类：只跟日历有关、不算你的记录 —— 今天几号、星期几、今年还剩几天、今年过了几成。见 DESIGN.md §8.5
 *
 * 不收日子当参数（离某天几天、某段日子走了几成都删了）：那等于模型往卡片里凭空记了一个日子。要数日子先建一条提醒，绑它。
 * 只绑了它的卡片闸门不收（`CardSpecs` 第 2 条）。[today] 测试里换掉
 */
class TimeSource(private val today: () -> LocalDate = { LocalDate.now() }) : CardSource {

    override val id = CardSpecs.TIME
    override val label = "时间"
    override val brief = "只跟日历有关；卡片不能只绑它。"

    override val fields = listOf(
        CardField("date", CardKind.TEXT, "今天几号「11 月 15 日」"),
        CardField("weekday", CardKind.TEXT, "今天星期几「周五」"),
        CardField("year_left", CardKind.NUMBER, "今年还剩几天"),
        CardField("year_progress", CardKind.NUMBER, "今年过了百分之几")
    )

    override suspend fun read(context: Context, name: String, args: List<String>): CardValue? = read(name)

    fun read(name: String): CardValue? {
        val t = today()
        return when (name) {
            "date" -> CardValue.Text("${t.monthValue} 月 ${t.dayOfMonth} 日")
            "weekday" -> CardValue.Text(WEEKDAYS[t.dayOfWeek.value - 1])
            "year_left" -> CardValue.Number(ChronoUnit.DAYS.between(t, LocalDate.of(t.year, 12, 31)))
            "year_progress" -> {
                val start = LocalDate.of(t.year, 1, 1)
                val total = ChronoUnit.DAYS.between(start, start.plusYears(1))
                CardValue.Number(ChronoUnit.DAYS.between(start, t) * 100 / total)
            }
            else -> null
        }
    }

    companion object {
        val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
