package com.abc.daodian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abc.daodian.ai.ChatTurn
import com.abc.daodian.ai.ParseResult
import com.abc.daodian.ai.PlanValidator
import com.abc.daodian.ai.ProviderProfile
import com.abc.daodian.ai.ReminderParser
import com.abc.daodian.ai.ReminderPlan
import com.abc.daodian.ai.ToolCallParser
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.notify.Notifier
import com.abc.daodian.schedule.Rescheduler
import com.abc.daodian.ui.chat.ChatMessage
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DaodianDatabase.get(app)
    private val rescheduler = Rescheduler(app)

    val profile = ProviderProfile.fromBuildConfig()
    private val parser: ReminderParser = ToolCallParser(profile)

    // ---------------- 对话 ----------------

    private val idGen = AtomicLong(0)
    private fun newId() = idGen.getAndIncrement()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    var aiBusy by mutableStateOf(false)
        private set

    /**
     * 一句话 → 模型 → 校验闸门 → 落库 → 排闹钟。见 DESIGN.md §6.1
     *
     * 工具调用一旦成功就已经建好了，卡片是回执不是待确认表单 ——
     * 「就这样」只是收起，「改一下」跳编辑页微调。
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || aiBusy) return

        viewModelScope.launch {
            // 新一轮开始，上一张还展开的卡片自动收起
            val collapsedPrev = _messages.value.map {
                if (it is ChatMessage.AssistantCard && !it.collapsed) it.copy(collapsed = true) else it
            }
            val history = historyOf(collapsedPrev)

            val thinkingId = newId()
            _messages.value = collapsedPrev + ChatMessage.UserText(newId(), trimmed) + ChatMessage.Thinking(thinkingId)
            aiBusy = true

            val result = parser.parse(trimmed, ZonedDateTime.now(), history)

            val reply: ChatMessage = when (result) {
                is ParseResult.Ok -> {
                    val reminderId = insertFromPlan(trimmed, result.plan)
                    ChatMessage.AssistantCard(newId(), reminderId, result.plan)
                }
                is ParseResult.NeedsClarification ->
                    ChatMessage.AssistantText(newId(), result.question)
                is ParseResult.Failed ->
                    ChatMessage.AssistantText(
                        newId(),
                        "连不上解析服务，可能是网络断了，或者服务配置不对。这条我先不建。",
                        isError = true
                    )
            }
            _messages.value = _messages.value.filterNot { it.id == thinkingId } + reply
            aiBusy = false
        }
    }

    fun collapseCard(id: Long) {
        _messages.value = _messages.value.map {
            if (it.id == id && it is ChatMessage.AssistantCard) it.copy(collapsed = true) else it
        }
    }

    /** 只取纯文本往返喂给下一轮请求，见 ChatTurn 的说明 */
    private fun historyOf(snapshot: List<ChatMessage>): List<ChatTurn> =
        snapshot.mapNotNull { m ->
            when (m) {
                is ChatMessage.UserText -> ChatTurn(fromUser = true, text = m.text)
                is ChatMessage.AssistantText -> ChatTurn(fromUser = false, text = m.text)
                else -> null
            }
        }

    private suspend fun insertFromPlan(rawInput: String, plan: ReminderPlan): Long {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val reminder = Reminder(
            title = plan.title,
            note = plan.note,
            rawInput = rawInput,
            nextTriggerAt = PlanValidator.triggerMillis(plan),
            rrule = plan.rrule,
            zoneId = zone.id,
            localTime = if (plan.wallClockAnchored) PlanValidator.localTimeOf(plan, zone) else null,
            wallClockAnchored = plan.wallClockAnchored,
            parsedBy = profile.model,
            createdAt = now,
            updatedAt = now
        )
        val id = db.reminderDao().insert(reminder)
        db.reminderDao().byId(id)?.let { rescheduler.schedule(it) }
        return id
    }

    // ---------------- 提醒 / 日志（列表、编辑、体检、投递日志共用）----------------

    val reminders = db.reminderDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs = db.fireLogDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 非 ALARM 来源的条数。大于 0 就说明主闹钟路径在被掐，见设计文档 §9.3 */
    val nonAlarmCount = db.fireLogDao().observeNonAlarmCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 手动建 / 改一条提醒 —— 逃生舱，必须能完全脱离 AI 用。见 DESIGN.md §05 */
    fun upsertManual(
        id: Long?,
        title: String,
        note: String?,
        triggerAt: Long,
        rrule: String?,
        wallClockAnchored: Boolean
    ) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val localTime = if (wallClockAnchored) {
            java.time.Instant.ofEpochMilli(triggerAt).atZone(zone).toLocalTime()
                .withSecond(0).withNano(0).toString()
        } else null

        if (id == null) {
            val reminder = Reminder(
                title = title, note = note, rawInput = title,
                nextTriggerAt = triggerAt, rrule = rrule, zoneId = zone.id,
                localTime = localTime, wallClockAnchored = wallClockAnchored,
                createdAt = now, updatedAt = now
            )
            val newId = db.reminderDao().insert(reminder)
            db.reminderDao().byId(newId)?.let { rescheduler.schedule(it) }
        } else {
            val existing = db.reminderDao().byId(id) ?: return@launch
            rescheduler.cancel(id)
            val updated = existing.copy(
                title = title, note = note, nextTriggerAt = triggerAt, rrule = rrule,
                localTime = localTime, wallClockAnchored = wallClockAnchored,
                status = ReminderStatus.SCHEDULED, updatedAt = now
            )
            db.reminderDao().update(updated)
            rescheduler.schedule(updated)
        }
    }

    fun addIn(title: String, minutes: Long) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val reminder = Reminder(
            title = title, rawInput = title,
            nextTriggerAt = now + TimeUnit.MINUTES.toMillis(minutes),
            zoneId = ZoneId.systemDefault().id,
            createdAt = now, updatedAt = now
        )
        val id = db.reminderDao().insert(reminder)
        db.reminderDao().byId(id)?.let { rescheduler.schedule(it) }
    }

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
