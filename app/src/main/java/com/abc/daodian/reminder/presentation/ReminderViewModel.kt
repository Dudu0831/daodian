package com.abc.daodian.reminder.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.assistant.plugin.CallHistory
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.reminder.scheduling.CheckTime
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.reminder.tools.UpdateReminderTool
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

    /** 收尾时刻：没说几点的、提前提醒、每天再提都在这个点。设置页改它 */
    val checkTime = CheckTime.flow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CheckTime.DEFAULT)

    fun setCheckTime(time: LocalTime) = viewModelScope.launch { Reminders.setCheckTime(getApplication(), time) }

    fun upsertManual(id: Long?, spec: ReminderSpec) = viewModelScope.launch { Reminders.upsertManual(getApplication(), id, spec) }

    /** 完成这一次（§4.6）：一次性的办完，重复的排下一次 */
    fun complete(r: Reminder) = viewModelScope.launch { Reminders.complete(getApplication(), r) }

    fun stop(r: Reminder) = viewModelScope.launch { Reminders.stop(getApplication(), r) }

    fun delete(r: Reminder) = viewModelScope.launch { Reminders.delete(getApplication(), r) }

    fun restore(r: Reminder) = viewModelScope.launch { Reminders.restore(getApplication(), r) }

    fun rescheduleAll() = viewModelScope.launch { Reminders.rescheduleAll(getApplication()) }

    /** 某条提醒当前是否真的有闹钟排着 —— 直接问 AlarmManager，不看数据库 */
    fun isArmed(id: Long): Boolean = rescheduler.isScheduled(id)

    /**
     * 这条提醒当初是模型怎么算出来的（create_reminder / update_reminder 的 basis）。模型算歪的时候，这是唯一能看出哪步歪了的线索 ——
     * 对话里不再有卡片，它挪到编辑页。桌面速记建的（不落盘）、手动建的没有。
     */
    suspend fun basisOf(reminderId: Long): String? =
        // 对话里改过（update_reminder）就看最近改的那次，它的时间才是现在这条的
        (CallHistory.arguments(getApplication(), UpdateReminderTool.NAME, reminderId)
            ?: CallHistory.arguments(getApplication(), CreateReminderTool.NAME, reminderId))
            ?.let(CreateReminderTool::planOf)?.basis?.takeIf { it.isNotBlank() }
}
