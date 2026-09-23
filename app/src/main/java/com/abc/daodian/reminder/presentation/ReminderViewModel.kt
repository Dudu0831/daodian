package com.abc.daodian.reminder.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.agent.conversation.data.ChatStore
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.scheduling.DayTasks
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.tools.CreateReminderTool
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 提醒的几页（列表、编辑、投递日志）和抽屉卡、设置组共用一份，按 Activity 取（shared/ui 的 activityViewModel）。
 * 读直接看 Room；写一律转给 [Reminders]。
 */
class ReminderViewModel(app: Application) : AndroidViewModel(app) {

    private val db = ReminderDatabase.get(app)
    private val rescheduler = Rescheduler(app)

    val reminders = db.reminderDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs = db.fireLogDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 非 ALARM 来源的条数。大于 0 就说明主闹钟路径在被掐，见设计文档 §9.3 */
    val nonAlarmCount = db.fireLogDao().observeNonAlarmCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 当天事项晚上几点提醒。设置页改它 */
    val dayCheckTime = DayTasks.checkTimeFlow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, DayTasks.DEFAULT_CHECK)

    fun setDayCheckTime(time: LocalTime) = viewModelScope.launch { Reminders.setDayCheckTime(getApplication(), time) }

    fun upsertManual(
        id: Long?,
        title: String,
        note: String?,
        triggerAt: Long,
        rrule: String?,
        wallClockAnchored: Boolean,
        dueDay: LocalDate? = null
    ) = viewModelScope.launch {
        Reminders.upsertManual(getApplication(), id, title, note, triggerAt, rrule, wallClockAnchored, dueDay)
    }

    fun markDone(r: Reminder) = viewModelScope.launch { Reminders.markDone(getApplication(), r) }

    fun delete(r: Reminder) = viewModelScope.launch { Reminders.delete(getApplication(), r) }

    fun restore(r: Reminder) = viewModelScope.launch { Reminders.restore(getApplication(), r) }

    fun rescheduleAll() = viewModelScope.launch { Reminders.rescheduleAll(getApplication()) }

    fun clearLogs() = viewModelScope.launch { Reminders.clearLogs(getApplication()) }

    /** 某条提醒当前是否真的有闹钟排着 —— 直接问 AlarmManager，不看数据库 */
    fun isArmed(id: Long): Boolean = rescheduler.isScheduled(id)

    /**
     * 这条提醒当初是模型怎么算出来的（create_reminder 的 basis）。模型算歪的时候，这是唯一能看出哪步歪了的线索 ——
     * 对话里不再有卡片，它挪到编辑页。桌面速记建的（不落盘）、手动建的没有。
     */
    suspend fun basisOf(reminderId: Long): String? =
        ChatStore.get(getApplication()).callArguments(CreateReminderTool.NAME, reminderId)
            ?.let(CreateReminderTool::planOf)?.basis?.takeIf { it.isNotBlank() }
}
