package com.abc.daodian.ui.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.abc.daodian.schedule.NotificationActionReceiver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 到点全屏页 —— 到点了真正弹出来的那个屏幕，不是 MainActivity。
 * 见 DESIGN.md §06、daodian-ui-mockups 的 AlarmFull。
 *
 * 「完成」「稍后 10 分钟」直接复用 NotificationActionReceiver 的逻辑（发同样的广播），
 * 不重复一份 DB/Rescheduler 代码。
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "到点了"
        val nowLabel = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())

        setContent {
            com.abc.daodian.ui.theme.DaodianTheme {
                AlarmScreen(
                    title = title,
                    nowLabel = nowLabel,
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
