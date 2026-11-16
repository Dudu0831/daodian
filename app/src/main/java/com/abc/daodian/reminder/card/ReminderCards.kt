package com.abc.daodian.reminder.card

import android.content.Context
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardRow
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.reminder.ReminderModule
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.dueDate
import com.abc.daodian.shared.format.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 提醒给桌面卡片的：某一条的日子、还剩几天，今天的几件、接下来几条，和「完成」这个动作（DESIGN.md §8.5）。
 * 卡片模块来问「days_left #7 现在是几」，这里只读库作答；点完成走 [Reminders.markDone]（先撤闹钟再改状态，同小组件的 ✓）。
 * 提醒有改动时 `widget/WidgetUpdater.refresh` 顺手喊卡片重画，这里不用另外喊。
 */
object ReminderCards : CardSource {

    override val id = ReminderModule.ID

    override val label = ReminderModule.LABEL

    override val brief = "#编号是提醒的 # 编号，用 list_reminders 查。卡片要数的那件事还没记成提醒，可以先 create_reminder 建一条，" +
        "也可以不建，用卡片自己的 date（只想在桌面上数日子、不用到点提醒时）。"

    private const val ID_ARG = "#编号"

    override val fields = listOf(
        CardField("title", CardKind.TEXT, "那条的标题", ID_ARG),
        CardField("date", CardKind.TEXT, "那条是哪天「2 月 13 日」，不是今年的带年份", ID_ARG),
        CardField("weekday", CardKind.TEXT, "那天星期几「周六」", ID_ARG),
        CardField("days_left", CardKind.NUMBER, "离那天还有几天（过了是 0）", ID_ARG),
        CardField("days_past", CardKind.NUMBER, "那天过了几天", ID_ARG),
        CardField("is_today", CardKind.FLAG, "是不是今天", ID_ARG),
        CardField("near", CardKind.FLAG, "是不是最后 7 天（含今天）", ID_ARG),
        CardField("past", CardKind.FLAG, "那天过了没", ID_ARG),
        CardField("done", CardKind.FLAG, "点完成了没", ID_ARG),
        CardField("progress", CardKind.NUMBER, "从起点走到那天百分之几（墨条用；起点不写就从建它那天算）", "$ID_ARG [起点日期]"),
        CardField("today", CardKind.ROWS, "今天要做的几件：标题、几点（当天事项写「当天」），行尾的圈点了就完成"),
        CardField("upcoming", CardKind.ROWS, "接下来几条，按先后：标题、什么时候，行尾的圈点了就完成"),
        CardField("count", CardKind.NUMBER, "一共几条还没响的"),
        CardField("today_count", CardKind.NUMBER, "今天还有几件"),
        CardField("next_title", CardKind.TEXT, "下一条的标题"),
        CardField("next_in", CardKind.TEXT, "下一条多久后「3 小时后」「今天之内」")
    )

    override val actions = listOf(CardField("done", CardKind.ACTION, "把那条点完成", ID_ARG))

    override suspend fun read(context: Context, name: String, args: List<String>): CardValue? {
        val dao = ReminderDatabase.get(context).reminderDao()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val one: Reminder? = args.firstOrNull()?.let { idOf(it) }?.let { dao.byId(it) }
        return when (name) {
            "count" -> CardValue.Number(dao.countScheduled().toLong())
            "today", "upcoming", "today_count", "next_title", "next_in" -> {
                val upcoming = ordered(dao.allScheduled(), today, zone)
                val todays = upcoming.filter { dayOf(it, zone) <= today }
                when (name) {
                    "today" -> CardValue.Rows(todays.map { row(it, today, zone) })
                    "upcoming" -> CardValue.Rows(upcoming.take(8).map { row(it, today, zone) }, upcoming.size)
                    "today_count" -> CardValue.Number(todays.size.toLong())
                    "next_title" -> upcoming.firstOrNull()?.let { CardValue.Text(it.title) }
                    else -> upcoming.firstOrNull()?.let { r ->
                        CardValue.Text(if (r.dueDate() != null && dayOf(r, zone) <= today) "今天之内" else Format.relative(r.nextTriggerAt, now))
                    }
                }
            }
            else -> one?.let { field(it, name, args, today, zone) }
        }
    }

    override suspend fun act(context: Context, name: String, args: List<String>): Boolean {
        if (name != "done") return false
        val id = args.firstOrNull()?.let { idOf(it) } ?: return false
        val r = ReminderDatabase.get(context).reminderDao().byId(id) ?: return false
        if (r.status != ReminderStatus.SCHEDULED && r.status != ReminderStatus.FIRED) return false
        Reminders.markDone(context, r)
        return true
    }

    /** 一条提醒的那几样。拆出来给测试用 */
    fun field(r: Reminder, name: String, args: List<String>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): CardValue? {
        val day = dayOf(r, zone)
        val days = ChronoUnit.DAYS.between(today, day)
        return when (name) {
            "title" -> CardValue.Text(r.title)
            "date" -> CardValue.Text((if (day.year == today.year) "" else "${day.year} 年 ") + "${day.monthValue} 月 ${day.dayOfMonth} 日")
            "weekday" -> CardValue.Text(Format.weekday(day.dayOfWeek))
            "days_left" -> CardValue.Number(days.coerceAtLeast(0))
            "days_past" -> CardValue.Number((-days).coerceAtLeast(0))
            "is_today" -> CardValue.Flag(days == 0L)
            "near" -> CardValue.Flag(days in 0..7)
            "past" -> CardValue.Flag(days < 0)
            "done" -> CardValue.Flag(r.status == ReminderStatus.DONE || r.status == ReminderStatus.CANCELLED)
            "progress" -> {
                val start = args.getOrNull(1)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                    ?: Instant.ofEpochMilli(r.createdAt).atZone(zone).toLocalDate()
                val total = ChronoUnit.DAYS.between(start, day).coerceAtLeast(1)
                CardValue.Number((ChronoUnit.DAYS.between(start, today) * 100 / total).coerceIn(0, 100))
            }
            else -> null
        }
    }

    /** 说的是哪一天：当天事项是它的日子（顺延、提前提醒都不改它），带钟点的是下一次响的那天 */
    fun dayOf(r: Reminder, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        r.dueDate() ?: Instant.ofEpochMilli(r.nextTriggerAt).atZone(zone).toLocalDate()

    private fun idOf(arg: String): Long? = arg.trim().removePrefix("#").toLongOrNull()

    /** 先后同桌面小组件：当天事项排在它那一天最前面（拖过来的算今天） */
    private fun ordered(all: List<Reminder>, today: LocalDate, zone: ZoneId): List<Reminder> = all.sortedBy { r ->
        val due = r.dueDate()
        if (due == null) r.nextTriggerAt else maxOf(due, today).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun row(r: Reminder, today: LocalDate, zone: ZoneId): CardRow {
        val day = maxOf(dayOf(r, zone), if (r.dueDate() != null) today else LocalDate.MIN)
        val dayWord = when (ChronoUnit.DAYS.between(today, day)) {
            0L -> ""
            1L -> "明天"
            else -> "${day.monthValue}月${day.dayOfMonth}日"
        }
        val note = if (r.dueDate() != null) dayWord.ifEmpty { "当天" } else listOf(dayWord, Format.clock(r.nextTriggerAt, zone)).filter { it.isNotEmpty() }.joinToString(" ")
        return CardRow(r.title, note, accent = r.dueDate() != null && day == today, action = "${ReminderModule.ID}.done #${r.id}")
    }
}
