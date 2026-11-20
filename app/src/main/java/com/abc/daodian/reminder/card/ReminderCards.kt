package com.abc.daodian.reminder.card

import android.content.Context
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardRow
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.reminder.ReminderModule
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.data.Phase
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.ReminderItem
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.slotTime
import com.abc.daodian.reminder.data.untimed
import com.abc.daodian.reminder.data.untimedDay
import com.abc.daodian.reminder.domain.Rrule
import com.abc.daodian.shared.format.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 提醒给桌面卡片的：某一条的日子、还剩几天、清单、几点、下一次响的那一刻，今天的几件、接下来几条，
 * 和「完成」「勾清单里一项」两个动作（DESIGN.md §8.5）。
 * 卡片模块来问「days_left #7 现在是几」，这里只读库作答；点完成走 [Reminders.complete]（只算这一次，同小组件的 ✓、提醒页的圈），
 * 勾清单走 [Reminders.setItemDone]。提醒、清单有改动时 `widget/WidgetUpdater.refresh` 顺手喊卡片重画，这里不用另外喊。
 * 要卡片多一种花样，多半是在这里多交一个名字，卡片模块不用改；不交算式能算出来的（「勾了几样」就单交一个）。
 */
object ReminderCards : CardSource {

    override val id = ReminderModule.ID

    override val label = ReminderModule.LABEL

    override val brief = "#编号是提醒的 # 编号，用 list_reminders 查。要数的那件事还没记成提醒，先 create_reminder 建一条再画；" +
        "只想在桌面上数日子、不用到点响铃的（春节），建成 ring=notice 的轻提醒。"

    private const val ID_ARG = "#编号"

    override val fields = listOf(
        CardField("title", CardKind.TEXT, "那条的标题", ID_ARG),
        CardField("date", CardKind.TEXT, "那条是哪天「2 月 13 日」，不是今年的带年份", ID_ARG),
        CardField("weekday", CardKind.TEXT, "那天星期几「周六」", ID_ARG),
        CardField("time", CardKind.TEXT, "这一次几点「18:00」，不定钟点的写「当天」", ID_ARG),
        CardField("repeat", CardKind.TEXT, "怎么重复「每月 15 日」，不重复的是空的", ID_ARG),
        CardField("next_nudge", CardKind.TEXT, "下一次提前提醒是哪天「12 月 13 日」，没有是空的", ID_ARG),
        CardField("days_left", CardKind.NUMBER, "离那天还有几天（过了是 0）", ID_ARG),
        CardField("days_past", CardKind.NUMBER, "那天过了几天", ID_ARG),
        CardField("is_today", CardKind.FLAG, "是不是今天", ID_ARG),
        CardField("near", CardKind.FLAG, "是不是最后 7 天（含今天）", ID_ARG),
        CardField("past", CardKind.FLAG, "那天过了没", ID_ARG),
        CardField("done", CardKind.FLAG, "点完成了没", ID_ARG),
        CardField("progress", CardKind.NUMBER, "从起点走到那天百分之几（墨条用；起点不写就从建它那天算）", "$ID_ARG [起点日期]"),
        CardField("fire_at", CardKind.MOMENT, "下一次响的那一刻（走秒用：三分钟后响的提醒就是泡面计时）", ID_ARG),
        CardField("items", CardKind.CHECKS, "那条的清单，每行一个方框，点了勾 / 取消", ID_ARG),
        CardField("items_left", CardKind.NUMBER, "清单还剩几样没勾", ID_ARG),
        CardField("items_checked", CardKind.NUMBER, "清单勾了几样", ID_ARG),
        CardField("items_total", CardKind.NUMBER, "清单一共几样", ID_ARG),
        CardField("items_done", CardKind.FLAG, "清单全勾完了没", ID_ARG),
        CardField("today", CardKind.ROWS, "今天要做的几件：标题、几点（不定钟点的写「当天」），行尾的圈点了就完成；value 是 0"),
        CardField("upcoming", CardKind.ROWS, "接下来几条，按先后：标题、什么时候，行尾的圈点了就完成；value 是离那天还有几天"),
        CardField("count", CardKind.NUMBER, "一共几条还没响的"),
        CardField("today_count", CardKind.NUMBER, "今天还有几件"),
        CardField("overdue", CardKind.NUMBER, "过了点还没点完成的几条"),
        CardField("next_title", CardKind.TEXT, "下一条的标题"),
        CardField("next_in", CardKind.TEXT, "下一条多久后「3 小时后」「今天之内」")
    )

    override val actions = listOf(
        CardField("done", CardKind.ACTION, "把那条点完成（只算这一次）", ID_ARG),
        CardField("check", CardKind.ACTION, "勾 / 取消清单里第几项（items 每一行自带，不用自己写）", "$ID_ARG 第几项")
    )

    override suspend fun read(context: Context, name: String, args: List<String>): CardValue? {
        val db = ReminderDatabase.get(context)
        val dao = db.reminderDao()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        return when (name) {
            "count" -> CardValue.Number(dao.countScheduled().toLong())
            "today", "upcoming", "today_count", "overdue", "next_title", "next_in" -> {
                val upcoming = ordered(dao.allScheduled(), today, zone)
                val todays = upcoming.filter { dayOf(it) <= today }
                when (name) {
                    "today" -> CardValue.Rows(todays.map { row(it, today, zone) })
                    "upcoming" -> CardValue.Rows(upcoming.take(8).map { row(it, today, zone) }, upcoming.size)
                    "today_count" -> CardValue.Number(todays.size.toLong())
                    "overdue" -> CardValue.Number(upcoming.count { overdue(it, now) }.toLong())
                    "next_title" -> upcoming.firstOrNull()?.let { CardValue.Text(it.title) }
                    else -> upcoming.firstOrNull()?.let { r ->
                        CardValue.Text(if (r.untimed && dayOf(r) <= today) "今天之内" else Format.relative(r.nextTriggerAt, now))
                    }
                }
            }
            else -> {
                val r = idOf(args.firstOrNull())?.let { dao.byId(it) } ?: return null
                val items = if (name.startsWith("items")) db.itemDao().of(r.id) else emptyList()
                field(r, name, args, today, zone, items)
            }
        }
    }

    override suspend fun act(context: Context, name: String, args: List<String>, on: Boolean?): Boolean {
        val id = idOf(args.firstOrNull()) ?: return false
        val db = ReminderDatabase.get(context)
        val r = db.reminderDao().byId(id) ?: return false
        return when (name) {
            "done" -> {
                // 卡片上取消「完成」的勾不算数：完成撤不回，下一张图会把勾画回去
                if (on == false) return false
                if (r.status != ReminderStatus.SCHEDULED && r.status != ReminderStatus.FIRED) return false
                Reminders.complete(context, r)
                true
            }
            "check" -> {
                val n = args.getOrNull(1)?.toIntOrNull() ?: return false
                val item = db.itemDao().of(id).getOrNull(n - 1) ?: return false
                val done = on ?: !item.done
                if (item.done != done) Reminders.setItemDone(context, item, done)
                true
            }
            else -> false
        }
    }

    /** 绑的是某一条（带 #编号）而那条删了 */
    override suspend fun gone(context: Context, name: String, args: List<String>): Boolean {
        val f = (fields + actions).firstOrNull { it.name == name } ?: return false
        if (!f.args.startsWith(ID_ARG)) return false
        val id = idOf(args.firstOrNull()) ?: return false
        return ReminderDatabase.get(context).reminderDao().byId(id) == null
    }

    /** 一条提醒的那几样。拆出来给测试用；[items] 是它的清单（只有 items 开头的要） */
    fun field(
        r: Reminder, name: String, args: List<String>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(),
        items: List<ReminderItem> = emptyList()
    ): CardValue? {
        val day = dayOf(r)
        val days = ChronoUnit.DAYS.between(today, day)
        val checked = items.count { it.done }
        val armed = r.status == ReminderStatus.SCHEDULED
        return when (name) {
            "title" -> CardValue.Text(r.title)
            "date" -> CardValue.Text(dateText(day, today))
            "weekday" -> CardValue.Text(Format.weekday(day.dayOfWeek))
            "time" -> CardValue.Text(r.slotTime()?.toString() ?: "当天")
            "repeat" -> CardValue.Text(Rrule.human(r.rrule).orEmpty())
            "next_nudge" -> CardValue.Text(
                if (armed && r.phase == Phase.NUDGE) dateText(Instant.ofEpochMilli(r.nextTriggerAt).atZone(zone).toLocalDate(), today) else ""
            )
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
            "fire_at" -> if (armed) CardValue.Moment(r.nextTriggerAt, Format.clock(r.nextTriggerAt, zone)) else null
            "items" -> CardValue.Rows(items.mapIndexed { i, it -> CardRow(it.text, done = it.done, action = "${ReminderModule.ID}.check #${r.id} ${i + 1}") })
            "items_left" -> CardValue.Number((items.size - checked).toLong())
            "items_checked" -> CardValue.Number(checked.toLong())
            "items_total" -> CardValue.Number(items.size.toLong())
            "items_done" -> CardValue.Flag(items.isNotEmpty() && checked == items.size)
            else -> null
        }
    }

    /** 说的是哪一天：这一次的那天（提前提醒、没点完成在催都不改它） */
    fun dayOf(r: Reminder): LocalDate = r.day

    /** 过了点还没点完成：在催着的、或者该响的那一下已经过了 */
    private fun overdue(r: Reminder, now: Long): Boolean = r.phase == Phase.NAG || (r.phase != Phase.NUDGE && r.nextTriggerAt <= now)

    /** 「2 月 13 日」，不是今年的带年份 */
    private fun dateText(day: LocalDate, today: LocalDate): String =
        (if (day.year == today.year) "" else "${day.year} 年 ") + "${day.monthValue} 月 ${day.dayOfMonth} 日"

    private fun idOf(arg: String?): Long? = arg?.trim()?.removePrefix("#")?.toLongOrNull()

    /** 先后同桌面小组件：当天事项排在它那一天最前面（拖过来的算今天） */
    private fun ordered(all: List<Reminder>, today: LocalDate, zone: ZoneId): List<Reminder> = all.sortedBy { r ->
        val due = r.untimedDay()
        if (due == null) r.nextTriggerAt else maxOf(due, today).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun row(r: Reminder, today: LocalDate, zone: ZoneId): CardRow {
        val day = maxOf(dayOf(r), if (r.untimed) today else LocalDate.MIN)
        val days = ChronoUnit.DAYS.between(today, day)
        val dayWord = when (days) {
            0L -> ""
            1L -> "明天"
            else -> "${day.monthValue}月${day.dayOfMonth}日"
        }
        val clock = r.slotTime()?.toString() ?: Format.clock(r.nextTriggerAt, zone)
        val note = if (r.untimed) dayWord.ifEmpty { "当天" } else listOf(dayWord, clock).filter { it.isNotEmpty() }.joinToString(" ")
        return CardRow(r.title, note, accent = r.untimed && day == today, action = "${ReminderModule.ID}.done #${r.id}", value = days.coerceAtLeast(0))
    }
}
