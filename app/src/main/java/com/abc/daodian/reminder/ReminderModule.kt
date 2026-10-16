package com.abc.daodian.reminder

import android.content.Context
import android.util.Log
import com.abc.daodian.module.Module
import com.abc.daodian.reminder.assistant.ReminderPlugin
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.presentation.ReminderShell
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.scheduling.SweepWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 提醒的接头。提醒本身不靠它活着：排期、到点、重排、巡检都在 scheduling / delivery 里，
 * 断网、不经过模型（手动填一条）照样响。这里只是把它交出去：给助手的（[ReminderPlugin]）、给壳的（[ReminderShell]），
 * 和冷启动要做的事。
 */
object ReminderModule : Module {

    const val ID = "reminder"
    const val LABEL = "提醒"

    override val id = ID
    override val label = LABEL
    override val assistant get() = ReminderPlugin
    override val shell get() = ReminderShell

    override fun onAppStart(context: Context) {
        val app = context.applicationContext
        Notifier.ensureChannel(app)
        SweepWorker.enqueue(app)
        // 冷启动也当作一次重排触发源 —— 被强杀后用户点开 app 就是最好的自愈时机
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { Rescheduler(app).rescheduleAll() }
                .onFailure { Log.e("Daodian/Reminder", "启动重排失败", it) }
        }
    }
}
