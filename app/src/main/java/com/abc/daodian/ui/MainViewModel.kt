package com.abc.daodian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abc.daodian.ai.ToolCallParser
import com.abc.daodian.ai.ParseResult
import com.abc.daodian.ai.PlanValidator
import com.abc.daodian.ai.ProviderProfile
import com.abc.daodian.ai.ReminderParser
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.FireLog
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.notify.Notifier
import com.abc.daodian.schedule.Rescheduler
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DaodianDatabase.get(app)
    private val rescheduler = Rescheduler(app)

    // ---- M2 验证用 ----------------------------------------------------
    val profile = ProviderProfile.fromBuildConfig()
    private val parser: ReminderParser = ToolCallParser(profile)

    /** null = 还没跑过；用于界面展示解析结果 */
    var aiBusy by mutableStateOf(false)
        private set
    var aiResult by mutableStateOf<String?>(null)
        private set

    /**
     * 端到端：一句话 → 模型 → 校验闸门 → 真的建一条提醒。
     * 这是 M2 的核心链路，先做成一个测试按钮。
     */
    fun aiParseAndAdd(text: String) = viewModelScope.launch {
        aiBusy = true
        aiResult = null
        val now = ZonedDateTime.now()
        val started = System.currentTimeMillis()
        when (val r = parser.parse(text, now)) {
            is ParseResult.Ok -> {
                val p = r.plan
                val at = PlanValidator.triggerMillis(p)
                add(p.title, at, p.rrule)
                aiResult = buildString {
                    appendLine("✓ 已建提醒（耗时 ${System.currentTimeMillis() - started}ms）")
                    appendLine("标题：${p.title}")
                    appendLine("时间：${p.firstTriggerAt}")
                    appendLine("依据：${p.basis}")
                    appendLine("重复：${p.rrule ?: "无"}")
                    append("置信度：${p.confidence}")
                }
            }
            is ParseResult.NeedsClarification ->
                aiResult = "? 需要澄清（耗时 ${System.currentTimeMillis() - started}ms）\n${r.question}"
            is ParseResult.Failed ->
                aiResult = "✗ 失败（耗时 ${System.currentTimeMillis() - started}ms）\n${r.reason}"
        }
        aiBusy = false
    }
    // -------------------------------------------------------------------

    val reminders = db.reminderDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs = db.fireLogDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 非 ALARM 来源的条数。大于 0 就说明主闹钟路径在被掐，见设计文档 §9.3 */
    val nonAlarmCount = db.fireLogDao().observeNonAlarmCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun add(title: String, triggerAt: Long, rrule: String? = null) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val id = db.reminderDao().insert(
            Reminder(
                title = title,
                rawInput = title,
                nextTriggerAt = triggerAt,
                rrule = rrule,
                zoneId = ZoneId.systemDefault().id,
                wallClockAnchored = rrule != null,
                createdAt = now,
                updatedAt = now
            )
        )
        db.reminderDao().byId(id)?.let { rescheduler.schedule(it) }
    }

    fun addIn(title: String, minutes: Long) =
        add(title, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes))

    /**
     * M1 的出口条件：20 条覆盖未来 48 小时、必然包含凌晨时段的提醒。
     * 排完就把手机揣兜里别碰，48 小时后回来看日志。见设计文档 §9.3
     */
    fun startSoakTest() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val step = TimeUnit.HOURS.toMillis(48) / 20
        repeat(20) { i ->
            val at = now + step * (i + 1)
            val r = Reminder(
                title = "放置测试 #${i + 1}",
                rawInput = "soak",
                nextTriggerAt = at,
                zoneId = ZoneId.systemDefault().id,
                createdAt = now,
                updatedAt = now
            )
            val id = db.reminderDao().insert(r)
            db.reminderDao().byId(id)?.let { rescheduler.schedule(it) }
        }
    }

    fun markDone(r: Reminder) = viewModelScope.launch {
        rescheduler.cancel(r.id)
        Notifier.cancel(getApplication(), r.id)
        db.reminderDao().setStatus(r.id, ReminderStatus.DONE, System.currentTimeMillis())
    }

    fun delete(r: Reminder) = viewModelScope.launch {
        rescheduler.cancel(r.id)
        Notifier.cancel(getApplication(), r.id)
        db.reminderDao().delete(r)
    }

    fun rescheduleAll() = viewModelScope.launch { rescheduler.rescheduleAll() }

    fun clearLogs() = viewModelScope.launch { db.fireLogDao().clear() }

    /** 某条提醒当前是否真的有闹钟排着 —— 直接问 AlarmManager，不看数据库 */
    fun isArmed(id: Long): Boolean = rescheduler.isScheduled(id)
}
