package com.abc.daodian.widget

import android.content.Context
import android.content.Intent
import com.abc.daodian.MainActivity

/**
 * 小组件点进 app 的四个去处。
 *
 * 用 Intent extra 而不是 Navigation 的 deep link URI：
 * 路由表是 [com.abc.daodian.ui.DaodianNavHost] 的私事（`ui/` 整包可丢弃，见 CLAUDE.md），
 * 小组件只说「我要去编辑第 7 条」，具体是哪个 route 字符串由 NavHost 自己决定。
 */
sealed interface WidgetTarget {
    /** 对话页，也就是正常打开 app */
    data object Chat : WidgetTarget
    /** 全部提醒列表 */
    data object List : WidgetTarget
    /** 手动新建一条（逃生舱，不经过 AI） */
    data object New : WidgetTarget
    /** 编辑已有的一条 */
    data class Edit(val reminderId: Long) : WidgetTarget
}

object WidgetLaunch {

    private const val EXTRA_TARGET = "com.abc.daodian.widget.TARGET"
    private const val EXTRA_REMINDER_ID = "com.abc.daodian.widget.REMINDER_ID"

    fun intent(context: Context, target: WidgetTarget): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(
                EXTRA_TARGET,
                when (target) {
                    WidgetTarget.Chat -> "chat"
                    WidgetTarget.List -> "list"
                    WidgetTarget.New -> "new"
                    is WidgetTarget.Edit -> "edit"
                }
            )
            .apply { if (target is WidgetTarget.Edit) putExtra(EXTRA_REMINDER_ID, target.reminderId) }

    /** MainActivity 用它把 intent 翻回去处。不是从小组件来的返回 null */
    fun targetOf(intent: Intent?): WidgetTarget? = when (intent?.getStringExtra(EXTRA_TARGET)) {
        "chat" -> WidgetTarget.Chat
        "list" -> WidgetTarget.List
        "new" -> WidgetTarget.New
        "edit" -> intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
            .takeIf { it >= 0 }
            ?.let { WidgetTarget.Edit(it) }
        else -> null
    }
}
