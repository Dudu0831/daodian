package com.abc.daodian.reminder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.abc.daodian.R
import com.abc.daodian.agent.entry.quick.QuickAddActivity
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.dueDate
import com.abc.daodian.reminder.data.isAllDay
import com.abc.daodian.shared.format.Format
import com.abc.daodian.agent.shell.ShellRoutes
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.shared.navigation.Launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 把数据库里的前几条提醒画成 RemoteViews。见 DESIGN.md §8.2
 *
 * 版面自上而下：抬头 → 提醒 → 脚（左边一行小字，右下角一枚墨印）。提醒怎么摆看条数和高度（[fit]）：
 * 只有一条是大字时钟；两三条挤在 3×2 / 4×2 里是一行一条的时间表；拉高到 3×3 / 4×3 起是大字时钟 + 底下几行。
 * 墨印永远在；高度不够时先砍行，再把「下一条」压成脚上的一行小字 ——
 * 小组件最要紧的是「在桌面上说一句」，其次才是「看一眼」。
 *
 * 用户会把它拖成 4×2、4×3、4×4：每个高度台阶各画一版，整张表交给桌面（[shapesFor]），
 * 桌面按小组件**实际量出来的**大小自己挑 —— 拖大拖小不用等我们重画，也不看桌面报的尺寸（荣耀报的宽是错的）。
 *
 * 一条硬规矩：**这里只读不写**。小组件是展示面，Room 依旧是唯一真相，
 * 排期/撤销一律走 Rescheduler（见 §5.3）。
 */
object WidgetRenderer {

    /** 上下内边距 + 抬头 + 脚（含上边距）+ 列表顶边距，和 widget_container.xml 对得上 */
    private const val CHROME_DP = 100

    /** 「下一条」只剩一行（大字时钟 + 标题），「明天 · …」挪到脚上和墨印并排 */
    private const val HERO_DP = 40

    /** 下面还要摆行时，「明天 · …」回到时钟底下，外加一根分隔线。和 widget_hero.xml 对得上 */
    private const val HERO_FULL_DP = 68

    /**
     * 一行的高度，和 widget_row.xml 对得上。26dp 是算出来的：3×2 的内容区 83dp（183 减 [CHROME_DP]），
     * 三行 78dp，再留几 dp 给「桌面量出来比报的矮一点」。
     */
    private const val ROW_DP = 26

    /** 最多几行：3×2 里是三条的时间表，再高是大字时钟 + 底下三行。桌面不是列表页，看完前几条就该点进 app */
    private const val MAX_ROWS = 3

    /** 版式只按高度分台阶，宽度不挑：每一版都放得进最窄的 3 格（和 widget_info.xml 的 minResizeWidth 对得上） */
    private const val MIN_WIDTH_DP = 160f

    /**
     * 刚从桌面速记建好的那条，和它亮到什么时候。只活在进程内存里 ——
     * 进程死了它就没了，下一次重画自然褪掉，不会一直挂着「刚记下」。见 [WidgetUpdater.announce]
     */
    @Volatile
    private var fresh: Pair<Long, Long>? = null

    fun markFresh(reminderId: Long, until: Long) {
        fresh = reminderId to until
    }

    suspend fun render(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return

        val dao = ReminderDatabase.get(context).reminderDao()
        val now = System.currentTimeMillis()
        val upcoming = ordered(dao.allScheduled(), now).take(1 + MAX_ROWS)
        val total = dao.countScheduled()
        val freshId = fresh?.takeIf { it.second > now }?.first

        val views = RemoteViews(
            shapesFor(upcoming.size).associate { (minHeightDp, fit) ->
                SizeF(MIN_WIDTH_DP, minHeightDp.toFloat()) to build(context, upcoming, total, fit, now, freshId)
            }
        )
        widgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    /**
     * 桌面上的先后：定时提醒按钟点；当天事项排在它那一天的最前面（今天的、拖过来的都算今天）——
     * 早上看一眼桌面就该知道今天有哪几件事要做，这也是它不另外发早上提醒的理由。见 DESIGN.md §4.3
     */
    private fun ordered(all: List<Reminder>, now: Long): List<Reminder> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return all.sortedBy { r ->
            val due = r.dueDate()
            if (due == null) r.nextTriggerAt
            else maxOf(due, today).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    /**
     * - [Shape.Hero]：只有一条（或者矮到只摆得下一条）—— 大字时钟，「今天 · 25 分钟后」写在脚上
     * - [Shape.HeroRows]：够高（3×3 / 4×3 起）—— 大字时钟 + 底下几行
     * - [Shape.Rows]：两三条挤在 3×2 / 4×2 里 —— 一行一条的时间表。大字时钟加一行要 94dp，3×2 只有 83dp；
     *   早先这时一行都不摆，明明两条只看得见一条、中间空一大块，用户在真机上问起才发现
     * - [Shape.FootOnly]：矮到一行都放不下 —— 下一条压成脚上的一行小字
     */
    private enum class Shape { Hero, HeroRows, Rows, FootOnly }

    /** [rows] = 摆几行（[Shape.HeroRows] 里不算大字时钟那一条） */
    private data class Fit(val shape: Shape, val rows: Int)

    /** 内容区高 [budget] dp、一共 [count] 条时怎么摆 */
    private fun fit(budget: Int, count: Int): Fit = when {
        count <= 1 -> if (budget >= HERO_DP) Fit(Shape.Hero, 0) else Fit(Shape.FootOnly, 0)
        budget >= HERO_FULL_DP + ROW_DP ->
            Fit(Shape.HeroRows, minOf(count - 1, MAX_ROWS, (budget - HERO_FULL_DP) / ROW_DP))
        budget >= 2 * ROW_DP -> Fit(Shape.Rows, minOf(count, MAX_ROWS, budget / ROW_DP))
        budget >= HERO_DP -> Fit(Shape.Hero, 0)
        else -> Fit(Shape.FootOnly, 0)
    }

    /**
     * [count] 条提醒在各个高度下分别摆成什么样：[(从多高起用它, 版式)]，高度是整块小组件的 dp。
     * 台阶就是 [fit] 里那几个门槛，只留版式真的变了的。桌面挑「放得下的里面最高的那一版」，
     * 一版都放不下就用最矮的 —— 所以最矮那一版的门槛写 [CHROME_DP]。
     */
    private fun shapesFor(count: Int): List<Pair<Int, Fit>> {
        val budgets = listOf(0, HERO_DP, 2 * ROW_DP, 3 * ROW_DP) + (1..MAX_ROWS).map { HERO_FULL_DP + it * ROW_DP }
        val out = mutableListOf<Pair<Int, Fit>>()
        budgets.sorted().forEach { budget ->
            val fit = fit(budget, count)
            if (out.lastOrNull()?.second != fit) out += (CHROME_DP + budget) to fit
        }
        return out
    }

    private fun build(
        context: Context,
        items: List<Reminder>,
        total: Int,
        fit: Fit,
        now: Long,
        freshId: Long?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_container)

        // 墨印 → 桌面速记，一进来就开始听；其余空白 → 进 app（对话页）；抬头 → 列表；行 → 编辑；圈 → 完成。
        // 纸要从整块小组件里长出来，整块的框靠「点空白处进 app」那一下记住，见 WidgetFrame
        views.setOnClickPendingIntent(android.R.id.background, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_mic, quickAdd(context))
        views.setOnClickPendingIntent(R.id.widget_header, activity(context, RC_LIST, ReminderRoutes.LIST))

        views.removeAllViews(R.id.widget_list)
        val empty = items.isEmpty()
        views.setViewVisibility(R.id.widget_empty, if (empty) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_list, if (empty) View.GONE else View.VISIBLE)

        if (empty) {
            val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
            views.setTextViewText(R.id.widget_greeting, Format.greeting(hour))
            views.setTextViewText(R.id.widget_count, dateLabel(now))
            views.setTextViewText(R.id.widget_foot, context.getString(R.string.widget_empty))
            return views
        }

        val next = items.first()
        val shown = when (fit.shape) {
            Shape.Hero -> {
                views.addView(R.id.widget_list, hero(context, next, now, freshId, full = false))
                1
            }
            Shape.HeroRows -> {
                val rest = items.drop(1).take(fit.rows)
                views.addView(R.id.widget_list, hero(context, next, now, freshId, full = rest.isNotEmpty()))
                rest.forEach { views.addView(R.id.widget_list, row(context, it, now, freshId, lead = false)) }
                1 + rest.size
            }
            Shape.Rows -> {
                val rows = items.take(fit.rows)
                // 第一条是「下一条」：时间用墨色，其余灰 —— 没有大字时钟了，一眼还得分得出先后
                rows.forEachIndexed { i, r -> views.addView(R.id.widget_list, row(context, r, now, freshId, lead = i == 0)) }
                rows.size
            }
            Shape.FootOnly -> 0
        }

        val hidden = total - shown
        views.setTextViewText(R.id.widget_count, context.getString(R.string.widget_count, total))
        views.setTextViewText(
            R.id.widget_foot,
            when {
                fit.shape == Shape.FootOnly -> context.getString(R.string.widget_next, whenShort(next, now), next.title)
                // 只有大字时钟那一条：「明天 · 23 小时后」写在脚上，和墨印并排
                fit.shape == Shape.Hero -> heroWhen(next, now, next.id == freshId)
                hidden > 0 -> context.getString(R.string.widget_more, hidden)
                // 时间表里只写了钟点，「还有多久」挪到脚上。当天事项没有「还有多久」，数的是下一个定时的
                fit.shape == Shape.Rows -> (items.firstOrNull { !it.isAllDay } ?: next).let {
                    context.getString(R.string.widget_next_in, Format.relative(it.nextTriggerAt, now))
                }
                else -> ""
            }
        )
        if (fit.shape == Shape.Hero) {
            tint(views, R.id.widget_foot, overdue = next.nextTriggerAt <= now, fresh = next.id == freshId)
        }
        return views
    }

    /** [full] = 下面还有行：「明天 · …」回到时钟底下，外加一根分隔线 */
    private fun hero(context: Context, reminder: Reminder, now: Long, freshId: Long?, full: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_hero)
        val isFresh = reminder.id == freshId

        // 当天事项没有钟点，大字写「今天」「明天」
        views.setTextViewText(
            R.id.hero_time,
            reminder.dueDate()?.let { dayLabelOf(maxOf(it, today(now)), now) } ?: Format.clock(reminder.nextTriggerAt)
        )
        views.setTextViewText(R.id.hero_title, reminder.title)
        if (full) {
            views.setTextViewText(R.id.hero_when, heroWhen(reminder, now, isFresh))
            tint(views, R.id.hero_when, overdue = reminder.nextTriggerAt <= now, fresh = isFresh)
            views.setViewVisibility(R.id.hero_when, View.VISIBLE)
            views.setViewVisibility(R.id.hero_rule, View.VISIBLE)
        }

        views.setOnClickPendingIntent(R.id.hero_root, activity(context, reminder.id.toInt(), ReminderRoutes.edit(reminder.id)))
        views.setOnClickPendingIntent(R.id.hero_done, done(context, reminder.id))
        return views
    }

    /** [lead] = 时间表里的第一条（没有大字时钟时的「下一条」），时间用墨色 */
    private fun row(context: Context, reminder: Reminder, now: Long, freshId: Long?, lead: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_row)
        val isFresh = reminder.id == freshId
        val overdue = reminder.nextTriggerAt <= now

        views.setTextViewText(R.id.row_title, reminder.title)
        views.setTextViewText(R.id.row_time, if (isFresh) "刚记下" else whenShort(reminder, now))
        // 朱砂只点在刚记下的那一条上，其余一律灰点 —— 满屏印章就不是印章了
        views.setImageViewResource(R.id.row_dot, if (isFresh) R.drawable.widget_dot else R.drawable.widget_dot_muted)
        tint(views, R.id.row_time, overdue = overdue, fresh = isFresh)
        if (lead && !overdue && !isFresh) views.setColor(R.id.row_time, "setTextColor", R.color.widget_ink)

        // 每条提醒各占一个 requestCode —— PendingIntent 比对时不看 extra，
        // 共用 0 的话所有行会指向同一条提醒。
        views.setOnClickPendingIntent(R.id.row_root, activity(context, reminder.id.toInt(), ReminderRoutes.edit(reminder.id)))
        views.setOnClickPendingIntent(R.id.row_done, done(context, reminder.id))
        return views
    }

    /** 「刚记下 · 明天 · 每天 · 23 小时后」 */
    private fun heroWhen(reminder: Reminder, now: Long, fresh: Boolean): String = buildString {
        if (fresh) append("刚记下 · ")
        reminder.dueDate()?.let { due ->
            // 「今天之内 · 20:00 提醒」「拖了 1 天 · 今晚 20:00 再提醒」
            append(Format.dayTaskWhen(due, today(now)))
            Format.humanRrule(reminder.rrule)?.let { append(" · ").append(it) }
            append(" · ").append(dayLabel(reminder.nextTriggerAt, now)).append(' ')
            append(Format.clock(reminder.nextTriggerAt)).append(" 提醒")
            return@buildString
        }
        append(dayLabel(reminder.nextTriggerAt, now))
        Format.humanRrule(reminder.rrule)?.let { append(" · ").append(it) }
        append(" · ")
        append(Format.relative(reminder.nextTriggerAt, now))
    }

    /**
     * 已经过点却还挂着的，时间写成红字：主闹钟路径被 ROM 掐掉的时候，这是桌面上第一眼能看见的告警
     * （配合 §9.3 的投递日志）。刚记下的那条写成朱砂。
     *
     * 颜色用 setColor + @ColorRes 交给桌面去解析 —— 在这边 getColor 算好塞过去的色值，
     * 用户切深浅色时会僵在原地。见 DESIGN.md §8.2 第三条
     */
    private fun tint(views: RemoteViews, viewId: Int, overdue: Boolean, fresh: Boolean) {
        when {
            overdue -> views.setColor(viewId, "setTextColor", R.color.widget_red)
            fresh -> views.setColor(viewId, "setTextColor", R.color.widget_accent)
        }
    }

    /** 行尾那一小段：重复的写「每天 10:00」，一次性的写「明天 15:00」 */
    private fun whenShort(reminder: Reminder, now: Long): String {
        reminder.dueDate()?.let { due ->
            return Format.humanRrule(reminder.rrule)?.let { "$it · 当天" } ?: Format.dayTaskWhen(due, today(now))
        }
        val clock = Format.clock(reminder.nextTriggerAt)
        return Format.humanRrule(reminder.rrule)?.let { "$it $clock" }
            ?: "${dayLabel(reminder.nextTriggerAt, now)} $clock"
    }

    private val weekdays = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 「今天 / 明天 / 后天 / 周五 / 9月20日」—— 桌面上一眼要看懂的是哪天，不是几号 */
    private fun dayLabel(at: Long, now: Long): String =
        dayLabelOf(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate(), now)

    private fun today(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun dayLabelOf(day: LocalDate, now: Long): String {
        val today = today(now)
        return when (ChronoUnit.DAYS.between(today, day)) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            in 3L..6L -> weekdays[day.dayOfWeek.value - 1]
            else -> "${day.monthValue}月${day.dayOfMonth}日"
        }
    }

    /** 空状态抬头右边的「9月11日 周五」 */
    private fun dateLabel(now: Long): String {
        val z = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        return "${z.monthValue}月${z.dayOfMonth}日 ${weekdays[z.dayOfWeek.value - 1]}"
    }

    private fun done(context: Context, reminderId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            Intent(context, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_DONE)
                .putExtra(WidgetActionReceiver.EXTRA_REMINDER_ID, reminderId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun activity(context: Context, requestCode: Int, route: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Launch.intent(context, route = route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * 挂在整块小组件上：点空白处进 app。桌面点它时把整块的屏幕位置填进 sourceBounds，
     * 宿主 Activity 拿它记下整块的宽高（agent 的 WidgetFrame.remember），下次点墨印时纸才知道该从多大的框长出来。
     * FLAG_MUTABLE 的理由同 [quickAdd]。
     */
    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            RC_APP,
            Launch.intent(context, route = ShellRoutes.CHAT),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * 挂在墨印上。桌面点它时会把墨印的屏幕位置填进 intent 的 sourceBounds —— 纸就从它所在的那块长出来。
     *
     * 这一个必须是 FLAG_MUTABLE：IMMUTABLE 的 PendingIntent 会把桌面送来的 fill-in 整个丢掉，
     * sourceBounds 也在里面，真机上纸就只能从屏幕底部升起。放开的代价是桌面能往里补字段
     * （action / data / extras），但 intent 指名道姓发给我们自己的 activity，补了也改不了去处。
     */
    private fun quickAdd(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            RC_MIC,
            QuickAddActivity.intent(context),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // 抬头几个按钮的 requestCode 从高位往下取，不会和提醒 id 撞
    private const val RC_LIST = Int.MAX_VALUE - 2
    // 桌面速记用新号：避开上一版 IMMUTABLE 说话条用过的 MAX - 1，别和它混
    private const val RC_MIC = Int.MAX_VALUE - 3
    private const val RC_APP = Int.MAX_VALUE - 4
}
