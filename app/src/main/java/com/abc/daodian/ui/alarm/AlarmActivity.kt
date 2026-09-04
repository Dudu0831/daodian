package com.abc.daodian.ui.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.abc.daodian.schedule.NotificationActionReceiver

/**
 * 到点全屏页 —— 到点了真正弹出来的那个屏幕，不是 MainActivity。
 * 见 DESIGN.md §08 界面、视觉稿的 Alarm / AlarmDark 两块画板。
 *
 * 「完成」「稍后 10 分钟」直接复用 NotificationActionReceiver 的逻辑（发同样的广播），
 * 不重复一份 DB/Rescheduler 代码。
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "到点了"
        val now = System.currentTimeMillis()

        setContent {
            // 钉死深色：半夜三点是这一屏最常见的使用场景，见 DESIGN.md §8.1。
            // 视觉稿的浅色版也画得出来（AlarmScreen 直接读主题色板），改成
            // isSystemInDarkTheme() 就能跟随系统。
            com.abc.daodian.ui.theme.DaodianTheme(darkTheme = true) {
                AlarmScreen(
                    title = title,
                    dateLabel = com.abc.daodian.ui.common.Format.chineseDate(now),
                    clockLabel = com.abc.daodian.ui.common.Format.clock(now),
                    onDone = { act(NotificationActionReceiver.ACTION_DONE, reminderId) },
                    onSnooze = { act(NotificationActionReceiver.ACTION_SNOOZE, reminderId) }
                )
            }
        }
    }

    private fun act(action: String, reminderId: Long) {
        if (reminderId >= 0) {
            sendBroadcast(
                Intent(this, NotificationActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(NotificationActionReceiver.EXTRA_REMINDER_ID, reminderId)
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_TITLE = "title"
    }
}
