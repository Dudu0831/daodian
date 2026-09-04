package com.abc.daodian.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面小组件。见 DESIGN.md §8.2
 *
 * 画面要查 Room，而 onReceive 跑在主线程上 —— 所以每条路径都是
 * `goAsync()` + 协程。goAsync 必须在 onReceive 的同步段里调，
 * onUpdate 是 onReceive 直接调下来的，还算在里面。
 */
class DaodianWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = redraw(context, appWidgetIds)

    /** 用户把小组件拉大拉小了：行数跟着重算 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) = redraw(context, intArrayOf(appWidgetId))

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 跨零点、改时间、换时区都会让「3 小时后」「明天」这类字面变成谎话。
        // 这几条都是受保护广播，静态注册收得到。
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> redraw(context, null)
        }
    }

    private fun redraw(context: Context, ids: IntArray?) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (ids == null) {
                    WidgetUpdater.refresh(app)
                } else {
                    WidgetRenderer.render(app, AppWidgetManager.getInstance(app), ids)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "小组件重绘失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val TAG = "Daodian/Widget" }
}
