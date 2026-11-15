package com.abc.daodian.card

import com.abc.daodian.card.data.Card
import com.abc.daodian.card.plugin.CardSubject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 卡片的模板。名字存进库（`Card.template`），**不改**；加一种模板 = 这里加一项 + 一个 `CardWidget` 子类 + 一份布局。
 * 一种模板一个小组件（各有各的默认尺寸），桌面的小组件列表里各占一格。见 DESIGN.md §8.5
 */
enum class CardTemplate(val label: String) {
    /** 2×2：标题、一个大数字、日期 */
    BIG("大数字"),

    /** 4×1：标题、还剩几天、一根从起点走到那天的墨条 */
    BAR("墨条");

    companion object {
        fun of(name: String?): CardTemplate? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/**
 * 一张卡片此刻该写什么。纯算：给那件事（[CardSubject]，模块交的）、卡片、今天，出字和进度 —— 模型不参与，每次重画由代码现算，
 * 所以跨了零点它自己会变（`CardWidget` 收 DATE_CHANGED）。画成 RemoteViews 归 `CardRenderer`。
 *
 * 这里写「还剩 N 天」：提醒页只写具体日期（§4.4），桌面卡片是那条规矩的例外，它就是拿来数日子的。
 */
data class CardFace(
    val title: String,
    /** 大数字那一格：「92」，或者「今天」「办完了」这种字（[word]） */
    val big: String,
    val word: Boolean,
    /** 数字前面的小字：「过」。没有就是空 */
    val before: String,
    /** 数字后面的小字：「天」 */
    val after: String,
    /** 脚上那行：「2 月 13 日」，最后一周写「周六」 */
    val foot: String,
    /** 墨条右上角：「还剩 47 天」「今天」「过了 2 天」 */
    val left: String,
    /** 墨条走了百分之几 */
    val progress: Int,
    /** 墨条两头的日期 */
    val startLabel: String,
    val endLabel: String,
    val tone: Tone
) {
    /** [NEAR] 最后一周和当天，数字用朱砂；[GONE] 过了、办完了，数字褪成灰 */
    enum class Tone { NORMAL, NEAR, GONE }

    companion object {

        /** 最后几天算「快到了」 */
        const val NEAR_DAYS = 7L

        private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** 「2 月 13 日」；不是今年的带年份 */
        fun date(day: LocalDate, today: LocalDate): String =
            (if (day.year == today.year) "" else "${day.year} 年 ") + "${day.monthValue} 月 ${day.dayOfMonth} 日"

        fun of(subject: CardSubject, card: Card, today: LocalDate): CardFace {
            val day = subject.day
            val days = ChronoUnit.DAYS.between(today, day)
            val start = runCatching { LocalDate.parse(card.startDay) }.getOrNull()?.takeIf { it < day } ?: minOf(today, day.minusDays(1))
            val total = ChronoUnit.DAYS.between(start, day).coerceAtLeast(1)
            val progress = (ChronoUnit.DAYS.between(start, today) * 100 / total).toInt().coerceIn(0, 100)
            val done = subject.done
            val dateText = date(day, today)
            return CardFace(
                title = subject.title,
                big = when {
                    done -> "办完了"
                    days == 0L -> "今天"
                    else -> kotlin.math.abs(days).toString()
                },
                word = done || days == 0L,
                before = if (!done && days < 0) "过" else "",
                after = if (!done && days != 0L) "天" else "",
                // 最后一周写星期几：「周六」比「2 月 13 日」更像一个马上要到的日子
                foot = if (!done && days in 1 until NEAR_DAYS) WEEKDAYS[day.dayOfWeek.value - 1] else dateText,
                left = when {
                    done -> "办完了"
                    days > 0 -> "还剩 $days 天"
                    days == 0L -> "今天"
                    else -> "过了 ${-days} 天"
                },
                progress = if (done) 100 else progress,
                startLabel = date(start, today),
                endLabel = dateText,
                tone = when {
                    done || days < 0 -> Tone.GONE
                    days <= NEAR_DAYS -> Tone.NEAR
                    else -> Tone.NORMAL
                }
            )
        }
    }
}
