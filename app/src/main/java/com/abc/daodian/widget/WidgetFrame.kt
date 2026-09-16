package com.abc.daodian.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * 整块小组件在屏幕上的框 —— 桌面速记那张纸要从它里面长出来。见 DESIGN.md §8.3
 *
 * 点击只挂在墨印上，桌面随点击给的 sourceBounds 就只是墨印那一小圆：右边、下边据此倒推是准的
 * （内边距是死的），宽高却推不出来，而桌面报的尺寸在荣耀上是错的（报 224 / 302dp，实际 242dp）。
 * 所以借「点空白处进 app」那一下：那个点击挂在整块上，sourceBounds 就是整块的框，把宽高记下来。
 * 记的时候连桌面当时报的尺寸一起记；报的变了（用户拖成 4×2、4×3、4×4 了）就当记下的作废。
 * 作废之后也不是干用报的尺寸：记的时候顺手记下「实测比报的差多少」，拖完用「新报的 + 这个差」，
 * 下一次点空白处再校准回实测值。
 */
object WidgetFrame {

    private const val PREFS = "widget_frame"
    private const val KEY_W = "w"
    private const val KEY_H = "h"
    private const val KEY_REPORTED = "reported"
    private const val KEY_DW = "dw"
    private const val KEY_DH = "dh"

    /** 墨印离小组件右边、下边的距离，和 widget_container.xml 的 paddingEnd / paddingBottom 对得上 */
    private const val PADDING_DP = 10

    /** 点空白处进 app 的那一下：记下整块的宽高。从 app 图标、从某一行进来的不算 */
    fun remember(context: Context, intent: Intent?) {
        val bounds = intent?.sourceBounds ?: return
        if (WidgetLaunch.targetOf(intent) != WidgetTarget.Chat) return
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_W, bounds.width())
            .putInt(KEY_H, bounds.height())
            .putString(KEY_REPORTED, reported(context))
        // 顺便记下「实测比桌面报的差多少」：拖大拖小之后记下的宽高作废，就拿「新报的 + 这个差」顶上
        estimated(context)?.let { (w, h) ->
            editor.putInt(KEY_DW, bounds.width() - w).putInt(KEY_DH, bounds.height() - h)
        }
        editor.apply()
    }

    /**
     * 从墨印的框倒推整块的框。记下的还有效就用记下的；拖大拖小作废了，用「新报的 + 上次实测的差」；
     * 桌面上没有小组件就是 null。左边、上边夹在屏幕里 —— 估大了也别让纸从屏幕外面长进来
     */
    fun fromMic(context: Context, mic: Rect): Rect? {
        val (w, h) = remembered(context) ?: corrected(context) ?: return null
        val pad = (PADDING_DP * context.resources.displayMetrics.density).roundToInt()
        val right = mic.right + pad
        val bottom = mic.bottom + pad
        return Rect((right - w).coerceAtLeast(0), (bottom - h).coerceAtLeast(0), right, bottom)
    }

    /** 桌面报的尺寸，加上上次实测时记下的偏差（没实测过就是 0）。荣耀宽度就差 18dp */
    private fun corrected(context: Context): Pair<Int, Int>? {
        val (w, h) = estimated(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (w + prefs.getInt(KEY_DW, 0)) to (h + prefs.getInt(KEY_DH, 0))
    }

    private fun remembered(context: Context): Pair<Int, Int>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val w = prefs.getInt(KEY_W, 0)
        val h = prefs.getInt(KEY_H, 0)
        if (w <= 0 || h <= 0 || prefs.getString(KEY_REPORTED, null) != reported(context)) return null
        return w to h
    }

    /** 桌面报的尺寸：竖屏取 MIN_WIDTH × MAX_HEIGHT、横屏反过来，和 WidgetRenderer.heightDp 同一个约定 */
    private fun estimated(context: Context): Pair<Int, Int>? {
        val options = options(context) ?: return null
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wDp = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val hDp = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (wDp <= 0 || hDp <= 0) return null
        val density = context.resources.displayMetrics.density
        return (wDp * density).roundToInt() to (hDp * density).roundToInt()
    }

    /** 桌面报的四个尺寸拼成一串，只拿来比对「变没变」 */
    private fun reported(context: Context): String? = options(context)?.let { o ->
        listOf(
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        ).joinToString(",") { o.getInt(it).toString() }
    }

    /** 桌面上摆了不止一块也只看第一块 —— 自用 app，桌面上就一块 */
    private fun options(context: Context) = AppWidgetManager.getInstance(context).let { manager ->
        manager.getAppWidgetIds(ComponentName(context, DaodianWidget::class.java))
            .firstOrNull()
            ?.let { manager.getAppWidgetOptions(it) }
    }
}
