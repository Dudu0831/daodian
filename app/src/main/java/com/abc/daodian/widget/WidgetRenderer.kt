package com.abc.daodian.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.abc.daodian.R
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.Reminder
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.quick.QuickAddActivity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 把数据库里的前几条提醒画成 RemoteViews。见 DESIGN.md §8.2
 *
 * 版面自上而下：抬头 → 下一条（大字时钟）→ 其余几行 → 脚（左边一行小字，右下角一枚墨印）。
 * 墨印永远在；高度不够时先砍行，再把「下一条」压成脚上的一行小字 ——
 * 小组件最要紧的是「在桌面上说一句」，其次才是「看一眼」。
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

    /** 一行的高度，和 widget_row.xml 对得上 */
    private const val ROW_DP = 32

    /** 「下一条」之外最多再摆几行 —— 桌面不是列表页，看完前几条就该点进 app */
    private const val MAX_ROWS = 4

    /** 桌面还没报尺寸（刚拖上去那一下）：按 3×2 的样子先画 */
    private const val DEFAULT_HEIGHT_DP = 180

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

        val dao = DaodianDatabase.get(context).reminderDao()
        val upcoming = dao.upcoming(1 + MAX_ROWS)
        val total = dao.countScheduled()
        val now = System.currentTimeMillis()
        val freshId = fresh?.takeIf { it.second > now }?.first

        widgetIds.forEach { id ->
            val budget = heightDp(context, manager.getAppWidgetOptions(id)) - CHROME_DP
            val fit = Fit(
                hero = budget >= HERO_DP,
                rows = if (budget >= HERO_FULL_DP + ROW_DP) ((budget - HERO_FULL_DP) / ROW_DP).coerceAtMost(MAX_ROWS) else 0
            )
            manager.updateAppWidget(id, build(context, upcoming, total, fit, now, freshId))
        }
    }

    /** 这块小组件画得下什么 */
    private data class Fit(val hero: Boolean, val rows: Int)

    /**
     * 小组件现在有多高。竖屏取 MAX_HEIGHT、横屏取 MIN_HEIGHT —— AppWidgetManager 文档里的约定
     * （竖屏窄而高，横屏宽而矮）。早先一律取 MIN，在只竖屏的桌面上等于按横屏的矮个子排版，
     * 「下一条」会被直接砍掉。
     */
    private fun heightDp(context: Context, options: Bundle?): Int {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        return options?.getInt(key, 0)?.takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
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

        // 整块小组件（连同右下角墨印）→ 桌面速记，一进来就开始听；抬头 → 列表；行 → 编辑；圈 → 完成。
        // 墨印自己不挂点击：点它会落到整块上，桌面随点击给的 sourceBounds 就是整块小组件的框 ——
        // 纸要从整块长出来，只有这样才拿得到准确的框（桌面报的尺寸在荣耀上是错的，见 QuickAddActivity.anchorOf）
        views.setOnClickPendingIntent(android.R.id.background, quickAdd(context))
        views.setOnClickPendingIntent(R.id.widget_header, activity(context, RC_LIST, WidgetTarget.List))

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
        val rest = if (fit.hero) items.drop(1).take(fit.rows) else emptyList()
        if (fit.hero) views.addView(R.id.widget_list, hero(context, next, now, freshId, full = rest.isNotEmpty()))
        rest.forEach { views.addView(R.id.widget_list, row(context, it, now, freshId)) }

        val hidden = total - (if (fit.hero) 1 else 0) - rest.size
        views.setTextViewText(R.id.widget_count, context.getString(R.string.widget_count, total))
        views.setTextViewText(
            R.id.widget_foot,
            when {
                !fit.hero -> context.getString(R.string.widget_next, whenShort(next, now), next.title)
                // 下面没有别的行：「明天 · 23 小时后」写在脚上，和墨印并排
                rest.isEmpty() -> heroWhen(next, now, next.id == freshId)
                hidden > 0 -> context.getString(R.string.widget_more, hidden)
                else -> ""
            }
        )
        if (fit.hero && rest.isEmpty()) {
            tint(views, R.id.widget_foot, overdue = next.nextTriggerAt <= now, fresh = next.id == freshId)
        }
        return views
    }

    /** [full] = 下面还有行：「明天 · …」回到时钟底下，外加一根分隔线 */
    private fun hero(context: Context, reminder: Reminder, now: Long, freshId: Long?, full: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_hero)
        val isFresh = reminder.id == freshId

        views.setTextViewText(R.id.hero_time, Format.clock(reminder.nextTriggerAt))
        views.setTextViewText(R.id.hero_title, reminder.title)
        if (full) {
            views.setTextViewText(R.id.hero_when, heroWhen(reminder, now, isFresh))
            tint(views, R.id.hero_when, overdue = reminder.nextTriggerAt <= now, fresh = isFresh)
            views.setViewVisibility(R.id.hero_when, View.VISIBLE)
            views.setViewVisibility(R.id.hero_rule, View.VISIBLE)
        }

        views.setOnClickPendingIntent(R.id.hero_root, activity(context, reminder.id.toInt(), WidgetTarget.Edit(reminder.id)))
        views.setOnClickPendingIntent(R.id.hero_done, done(context, reminder.id))
        return views
    }

    private fun row(context: Context, reminder: Reminder, now: Long, freshId: Long?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_row)
        val isFresh = reminder.id == freshId

        views.setTextViewText(R.id.row_title, reminder.title)
        views.setTextViewText(R.id.row_time, if (isFresh) "刚记下" else whenShort(reminder, now))
        // 朱砂只点在刚记下的那一条上，其余一律灰点 —— 满屏印章就不是印章了
        views.setImageViewResource(R.id.row_dot, if (isFresh) R.drawable.widget_dot else R.drawable.widget_dot_muted)
        tint(views, R.id.row_time, overdue = reminder.nextTriggerAt <= now, fresh = isFresh)

        // 每条提醒各占一个 requestCode —— PendingIntent 比对时不看 extra，
        // 共用 0 的话所有行会指向同一条提醒。
        views.setOnClickPendingIntent(R.id.row_root, activity(context, reminder.id.toInt(), WidgetTarget.Edit(reminder.id)))
        views.setOnClickPendingIntent(R.id.row_done, done(context, reminder.id))
        return views
    }

    /** 「刚记下 · 明天 · 每天 · 23 小时后」 */
    private fun heroWhen(reminder: Reminder, now: Long, fresh: Boolean): String = buildString {
        if (fresh) append("刚记下 · ")
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
        val clock = Format.clock(reminder.nextTriggerAt)
        return Format.humanRrule(reminder.rrule)?.let { "$it $clock" }
            ?: "${dayLabel(reminder.nextTriggerAt, now)} $clock"
    }

    private val weekdays = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 「今天 / 明天 / 后天 / 周五 / 9月20日」—— 桌面上一眼要看懂的是哪天，不是几号 */
    private fun dayLabel(at: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
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

    private fun activity(context: Context, requestCode: Int, target: WidgetTarget): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            WidgetLaunch.intent(context, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * 挂在整块小组件上。桌面点它时会把整块的屏幕位置填进 intent 的 sourceBounds —— 纸就从那儿长出来。
     *
     * 这一个必须是 FLAG_MUTABLE：IMMUTABLE 的 PendingIntent 会把桌面送来的 fill-in 整个丢掉，
     * sourceBounds 也在里面，真机上纸就只能从屏幕底部升起。放开的代价是桌面能往里补字段
     * （action / data / extras），但 intent 指名道姓发给我们自己的 QuickAddActivity，
     * 那边除了 sourceBounds 什么都不读，补了也没用。
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
}
