package com.abc.daodian.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 「数据变了，桌面重画一次」的唯一入口。
 *
 * 桌面小组件收不到 Room 的 Flow —— 它在别的进程里。所以每个会改动提醒的地方
 * 都要主动喊一声：建/改/删（MainViewModel）、响铃（FireHandler）、
 * 完成/稍后（NotificationActionReceiver、WidgetActionReceiver）、重排（RescheduleReceiver）。
 *
 * 漏喊的后果是**桌面显示旧数据**，不是漏提醒 —— 触发链路完全不依赖这里。
 */
object WidgetUpdater {

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, DaodianWidget::class.java))
        if (ids.isEmpty()) return    // 桌面上一个都没放，省掉一次查库

        runCatching { WidgetRenderer.render(app, manager, ids) }
            .onFailure { Log.e("Daodian/Widget", "刷新小组件失败", it) }
    }
}
