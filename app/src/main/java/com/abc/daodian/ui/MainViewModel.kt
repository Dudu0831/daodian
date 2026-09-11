package com.abc.daodian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abc.daodian.ai.ChatTurn
import com.abc.daodian.ai.ParseEvent
import com.abc.daodian.ai.ParseResult
import com.abc.daodian.ai.PlanValidator
import com.abc.daodian.ai.ProviderProfile
import com.abc.daodian.ai.ReminderPlan
import com.abc.daodian.ai.StreamingReminderParser
import com.abc.daodian.ai.ToolCallParser
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.notify.Notifier
import com.abc.daodian.schedule.Rescheduler
import com.abc.daodian.ui.chat.ChatMessage
import com.abc.daodian.widget.WidgetUpdater
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
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
    private val parser: StreamingReminderParser = ToolCallParser(profile)

    init {
        // app 内的增删改一律走 Room，所以盯住这一条流就够了 ——
        // 不用在 upsert/markDone/delete 里各插一行刷新，也就不会漏。
        // app 没开着时的改动（响铃、通知按钮、巡检）由各自的调用点自己喊，见 WidgetUpdater。
        viewModelScope.launch {
            db.reminderDao().observeAll().collect { WidgetUpdater.refresh(app) }
        }
    }

    // ---------------- 对话 ----------------

    private val idGen = AtomicLong(0)
    private fun newId() = idGen.getAndIncrement()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    var aiBusy by mutableStateOf(false)
        private set

    /** 最后一句用户说的话，失败后「重试」用得上 */
    private var lastUserInput: String? = null

    /** 当前那条流。「停」和「换一句」都靠它掐断 */
    private var streamJob: Job? = null

    /**
     * 一句话 → 模型 → 校验闸门 → 落库 → 排闹钟。见 DESIGN.md §6.1
     *
     * 工具调用一旦成功就已经建好了，卡片是回执不是待确认表单 ——
     * 「就这样」只是收起，「改一下」跳编辑页微调。
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || aiBusy) return

        // 新一轮开始，上一张还展开的卡片自动收起
        val collapsedPrev = _messages.value.map {
            if (it is ChatMessage.AssistantTurn && it.plan != null && !it.cardCollapsed) {
                it.copy(cardCollapsed = true)
            } else it
        }
        val history = historyOf(collapsedPrev)

        lastUserInput = trimmed
        _messages.value = collapsedPrev + ChatMessage.UserText(newId(), trimmed)
        parseAndReply(trimmed, history)
    }

    /**
     * 解析失败后的「重试」。失败那一整个回合会被抹掉、也不进历史 ——
     * 不然下一轮模型会看到自己说过「连不上服务器」，然后顺着这个话头往下编。
     * 思考过程跟着一起走（它是上一次跑偏的痕迹），因为现在它就长在那个回合里。
     */
    fun retryLast() {
        val trimmed = lastUserInput ?: return
        if (aiBusy) return

        val kept = _messages.value.filterNot { it is ChatMessage.AssistantTurn && it.isError }
        val last = kept.lastOrNull()
        val prior = if (last is ChatMessage.UserText && last.text == trimmed) kept.dropLast(1) else kept

        _messages.value = kept
        parseAndReply(trimmed, historyOf(prior))
    }

    /**
     * 流式解析。见 DESIGN.md §6.7。
     *
     * 一个回合**原地长大**：思考 → 工具行 → 正文 → 卡片，中途不换消息类型。
     * 终局仍然是 [ParseResult]，底下「落库 → 排闹钟」那一段和非流式时代一模一样，
     * 没有第二条落库路径。
     */
    private fun parseAndReply(text: String, history: List<ChatTurn>) {
        streamJob = viewModelScope.launch {
            val turnId = newId()
            _messages.value = _messages.value + ChatMessage.AssistantTurn(turnId, streaming = true)
            aiBusy = true

            var result: ParseResult? = null
            try {
                parser.parseStream(text, ZonedDateTime.now(), history).collect { event ->
                    if (event is ParseEvent.Done) result = event.result else patchTurn(turnId, event)
                }
                finishTurn(turnId, text, result)
            } catch (t: CancellationException) {
                // 用户点了「停」：连同半截字一起撤掉，用户那句话留着，可以直接改了重发
                _messages.value = _messages.value.filterNot { it.id == turnId }
                throw t
            } finally {
                aiBusy = false
            }
        }
    }

    /** 把一个过程事件叠到那个回合上 */
    private fun patchTurn(turnId: Long, event: ParseEvent) {
        _messages.value = _messages.value.map { m ->
            if (m.id != turnId || m !is ChatMessage.AssistantTurn) m else when (event) {
                is ParseEvent.Reasoning -> m.copy(reasoning = m.reasoning + event.delta)
                is ParseEvent.Text -> m.copy(text = m.text + event.delta)
                is ParseEvent.ToolStarted -> m.copy(toolName = event.name, toolRunning = true)
                // 参数不上屏（见设计稿）—— 只用它证明工具还在跑
                is ParseEvent.ToolArgs -> m
                // 回退了：吐出来的半截全部作废，退回骨架条。
                // 留着的话，等下一次性结果一到，同一句话会像是被说了两遍
                ParseEvent.FellBack -> ChatMessage.AssistantTurn(m.id, streaming = true)
                is ParseEvent.Done -> m
            }
        }
    }

    /** 流走完了：卡片长在同一个回合里，正文和工具行按各自的规则留或藏 */
    private suspend fun finishTurn(turnId: Long, rawInput: String, result: ParseResult?) {
        val reminderId = (result as? ParseResult.Ok)?.let { insertFromPlan(rawInput, it.plan) }

        _messages.value = _messages.value.map { m ->
            if (m.id != turnId || m !is ChatMessage.AssistantTurn) m else when (result) {
                is ParseResult.Ok -> m.copy(
                    streaming = false, toolRunning = false,
                    reminderId = reminderId, plan = result.plan
                )
                is ParseResult.NeedsClarification -> m.copy(
                    streaming = false, toolRunning = false,
                    // 流里已经逐字吐过这句话了，别再覆盖一遍
                    text = m.text.ifBlank { result.question }
                )
                // 第二句「你可以自己填一条」由 UI 补，见 AssistantTurnRow
                is ParseResult.Failed, null -> m.copy(
                    streaming = false, toolRunning = false, isError = true,
                    text = "连不上服务器，这句话没能解析。"
                )
            }
        }
    }

    /** 「停」。流被取消时 StreamResponse 会被 close 掉，见 ToolCallParser.parseStream */
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    fun toggleReasoning(id: Long) {
        _messages.value = _messages.value.map {
            if (it.id == id && it is ChatMessage.AssistantTurn) it.copy(reasoningOpen = !it.reasoningOpen) else it
        }
    }

    fun collapseCard(id: Long) {
        _messages.value = _messages.value.map {
            if (it.id == id && it is ChatMessage.AssistantTurn) it.copy(cardCollapsed = true) else it
        }
    }

    /**
     * 把对话压成喂给下一轮的文本历史，见 ChatTurn 的说明。
     *
     * 已建的提醒必须以「回执」的形式留在历史里：否则模型看到的是
     * 「用户：三分钟后提醒我喝水 / 用户：谢谢」—— 上一句请求像是没人应，
     * 它会好心再建一遍。真机上就是这么冒出重复提醒的。
     */
    private fun historyOf(snapshot: List<ChatMessage>): List<ChatTurn> =
        snapshot.mapNotNull { m ->
            when (m) {
                is ChatMessage.UserText -> ChatTurn(fromUser = true, text = m.text)
                is ChatMessage.AssistantTurn -> {
                    // 思考过程不进历史：把模型自己的思考喂回给它没有意义，只会挤掉真正的上下文
                    val said = buildString {
                        append(m.text.trim())
                        m.plan?.let { plan ->
                            if (isNotEmpty()) append(" ")
                            append("（已建好提醒：「${plan.title}」，${plan.firstTriggerAt}")
                            plan.rrule?.let { append("，重复 $it") }
                            append("。这条已经落库排期了，不要重复建）")
                        }
                    }
                    said.takeIf { it.isNotBlank() }?.let { ChatTurn(fromUser = false, text = it) }
                }
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
