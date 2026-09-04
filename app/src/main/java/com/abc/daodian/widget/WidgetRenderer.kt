package com.abc.daodian.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.abc.daodian.R
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.Reminder
import com.abc.daodian.ui.common.Format

/**
 * 把数据库里的前几条提醒画成 RemoteViews。见 DESIGN.md §8.2
 *
 * 一条硬规矩：**这里只读不写**。小组件是展示面，Room 依旧是唯一真相，
 * 排期/撤销一律走 Rescheduler（见 §5.3）。
 */
object WidgetRenderer {

    /** 抬头（含分隔线）大概占的高度 */
    private const val HEADER_DP = 46

    /** 一行的高度，和 widget_row.xml 的内外边距对得上 */
    private const val ROW_DP = 46

    /** 再高也不多画 —— 桌面不是列表页，看完前几条就该点进 app */
    private const val MAX_ROWS = 8

    suspend fun render(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return

        val dao = DaodianDatabase.get(context).reminderDao()
        val upcoming = dao.upcoming(MAX_ROWS)
        val total = dao.countScheduled()
        val now = System.currentTimeMillis()

        widgetIds.forEach { id ->
            val rows = rowCapacity(manager.getAppWidgetOptions(id))
            manager.updateAppWidget(id, build(context, upcoming.take(rows), total, now))
        }
    }

    /**
     * 能塞几行，按桌面告诉我们的最小高度算。
     * 用 MIN_HEIGHT 而不是 MAX_HEIGHT：横竖屏两套尺寸里取小的那个，
     * 免得转屏之后行数超出可视区域被裁掉半行。
     */
    private fun rowCapacity(options: android.os.Bundle?): Int {
        val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        if (minHeight <= 0) return 2          // 桌面还没报尺寸（刚拖上去那一下）
        return ((minHeight - HEADER_DP) / ROW_DP).coerceIn(1, MAX_ROWS)
    }

    private fun build(
        context: Context,
        items: List<Reminder>,
        total: Int,
        now: Long
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_container)

        // 抬头：整块点开进对话页，＋ 进手动新建
        views.setOnClickPendingIntent(R.id.widget_root, activity(context, RC_ROOT, WidgetTarget.Chat))
        views.setOnClickPendingIntent(R.id.widget_brand, activity(context, RC_LIST, WidgetTarget.List))
        views.setOnClickPendingIntent(R.id.widget_count, activity(context, RC_LIST, WidgetTarget.List))
        views.setOnClickPendingIntent(R.id.widget_add, activity(context, RC_ADD, WidgetTarget.New))

        val hidden = total - items.size
        views.setTextViewText(
            R.id.widget_count,
            when {
                total == 0 -> ""
                hidden > 0 -> context.getString(R.string.widget_count_more, total, hidden)
                else -> context.getString(R.string.widget_count, total)
            }
        )

        views.removeAllViews(R.id.widget_list)
        views.setViewVisibility(R.id.widget_empty, if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
        views.setViewVisibility(R.id.widget_list, if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)

        items.forEach { views.addView(R.id.widget_list, row(context, it, now)) }
        return views
    }

    private fun row(context: Context, reminder: Reminder, now: Long): RemoteViews {
        val overdue = reminder.nextTriggerAt <= now
        val views = RemoteViews(context.packageName, R.layout.widget_row)

        views.setTextViewText(R.id.row_title, reminder.title)
        views.setTextViewText(R.id.row_time, timeLine(reminder, now))

        // 朱砂留给还没到点的；已经过点还挂在这儿的，说明触发链路慢了或被掐了，
        // 换成灰点 + 红字，让它自己招供。见 DESIGN.md §9.3
        views.setImageViewResource(
            R.id.row_dot,
            if (overdue) R.drawable.widget_dot_muted else R.drawable.widget_dot
        )
        views.setTextColor(
            R.id.row_time,
            context.getColor(if (overdue) R.color.widget_red else R.color.widget_muted)
        )

        // 每条提醒各占一个 requestCode —— PendingIntent 比对时不看 extra，
        // 共用 0 的话所有行会指向同一条提醒。
        val code = reminder.id.toInt()
        views.setOnClickPendingIntent(
            R.id.row_root,
            activity(context, code, WidgetTarget.Edit(reminder.id))
        )
        views.setOnClickPendingIntent(
            R.id.row_done,
            PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, WidgetActionReceiver::class.java)
                    .setAction(WidgetActionReceiver.ACTION_DONE)
                    .putExtra(WidgetActionReceiver.EXTRA_REMINDER_ID, reminder.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        return views
    }

    /** 「每天 · 9月2日 15:00 · 3 小时后」，重复规则没有就不占位 */
    private fun timeLine(reminder: Reminder, now: Long): String = buildString {
        Format.humanRrule(reminder.rrule)?.let { append(it).append(" · ") }
        append(Format.humanDateTimeShort(reminder.nextTriggerAt))
        append(" · ")
        append(Format.relative(reminder.nextTriggerAt, now))
    }

    private fun activity(context: Context, requestCode: Int, target: WidgetTarget): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            WidgetLaunch.intent(context, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // 抬头几个按钮的 requestCode 从高位往下取，不会和提醒 id 撞
    private const val RC_ROOT = Int.MAX_VALUE
    private const val RC_ADD = Int.MAX_VALUE - 1
    private const val RC_LIST = Int.MAX_VALUE - 2
}
