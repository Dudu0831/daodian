package com.abc.daodian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abc.daodian.harness.AgentEvent
import com.abc.daodian.harness.Session
import com.abc.daodian.harness.permission.PermissionGate
import com.abc.daodian.harness.permission.PermissionMode
import com.abc.daodian.harness.permission.PermissionStore
import com.abc.daodian.harness.provider.ApiHealth
import com.abc.daodian.harness.provider.PingResult
import com.abc.daodian.harness.provider.ProviderProfile
import com.abc.daodian.harness.provider.ProviderStore
import com.abc.daodian.harness.provider.ProviderTest
import com.abc.daodian.data.DaodianDatabase
import com.abc.daodian.data.chat.ChatStore
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.notify.Notifier
import com.abc.daodian.schedule.DayTasks
import com.abc.daodian.schedule.Rescheduler
import com.abc.daodian.ui.chat.ChatMessage
import com.abc.daodian.ui.chat.patched
import com.abc.daodian.ui.chat.restoredMessages
import com.abc.daodian.widget.WidgetUpdater
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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

    /**
     * 供应商配置。存在机器上、随时可改（[ProviderStore]），所以是一条流，不是一个常量 ——
     * 顶栏那枚印、桌面速记、下一句话用哪个模型，都从这里取当时的值。
     */
    val profile = ProviderStore.flow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProviderStore.seed)

    /** 上一次调用顺不顺，给印和纸签用。见 [ApiHealth] */
    val apiState = ApiHealth.state

    /** 授权模式：默认先问（[PermissionMode.ASK]），设置页能放开。见 DESIGN.md §6.8 */
    val permissionMode = PermissionStore.flow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PermissionMode.ASK)

    fun setPermissionMode(mode: PermissionMode) = viewModelScope.launch {
        PermissionStore.save(getApplication(), mode)
    }

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

    /**
     * 喂给模型的历史（完整结构），落盘在 `data/chat/`，重启接着聊。
     * [_messages] 是画面、这里是模型看到的，两者各管各的；启动时画面从它重建一次。
     * 读库要一会儿，用到它的地方都先 await —— 读完之前说的第一句话也不会丢上文。
     */
    private val session: Deferred<Session> = viewModelScope.async {
        val store = ChatStore.get(app)
        val turns = store.load()
        _messages.value = restoredMessages(turns, ::newId) + _messages.value
        Session(turns, store)
    }

    var aiBusy by mutableStateOf(false)
        private set

    /** 点了「停」之后要退回输入框的原话。界面取走后调 [consumeRestoredInput] */
    var restoredInput by mutableStateOf<String?>(null)
        private set

    /** 最后一句用户说的话，失败后「重试」用得上 */
    private var lastUserInput: String? = null

    /** 当前那一轮。「停」靠它掐断 */
    private var streamJob: Job? = null

    /** 这次取消是用户按的「停」，不是离开页面之类 */
    private var stoppedByUser = false

    /** 卡片停在「要记下吗」时，循环挂在这上面等 [answerApproval] */
    private var pendingApproval: CompletableDeferred<Boolean>? = null

    /**
     * 一句话 → agent 循环。见 DESIGN.md §6.8
     *
     * 提醒由 `create_reminder` 工具在循环里落库（[Agents] 里接的 [PlanCommitter]）。
     * 放开模式下卡片是回执；默认模式下卡片先停在草稿上，你点「记下」工具才执行。
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

        lastUserInput = trimmed
        _messages.value = collapsedPrev + ChatMessage.UserText(newId(), trimmed)
        runTurn(trimmed)
    }

    /**
     * 失败后的「重试」。失败那一整个回合会被抹掉，模型那边的记录也一起拿掉 ——
     * 不然下一轮模型会看到自己上一次跑偏的痕迹，顺着往下编。
     */
    fun retryLast() {
        val trimmed = lastUserInput ?: return
        if (aiBusy) return
        _messages.value = _messages.value.filterNot { it is ChatMessage.AssistantTurn && it.isError }
        runTurn(trimmed, retry = true)
    }

    /**
     * 跑一轮。一个回合**原地长大**：思考 → 卡片 → 正文，中途不换消息类型。见 DESIGN.md §6.7
     */
    private fun runTurn(text: String, retry: Boolean = false) {
        streamJob = viewModelScope.launch {
            val session = session.await()
            if (retry) session.discardLastTurn()
            val turnId = newId()
            _messages.value = _messages.value + ChatMessage.AssistantTurn(turnId, streaming = true)
            aiBusy = true

            val gate = PermissionGate(permissionMode.value) { _, _ ->
                CompletableDeferred<Boolean>().also { pendingApproval = it }.await()
            }
            try {
                Agents.of(getApplication(), profile.value)
                    .run(session, text, ZonedDateTime.now(), gate)
                    .collect { event ->
                        patchTurn(turnId, event)
                        if (event is AgentEvent.Finished || event is AgentEvent.Failed) ApiHealth.record(event)
                    }
            } catch (t: CancellationException) {
                val turn = _messages.value.firstOrNull { it.id == turnId } as? ChatMessage.AssistantTurn
                if (turn?.plan != null) {
                    // 提醒已经建了：回合留着（卡片是真的），只是不再往下长
                    _messages.value = _messages.value.map {
                        if (it.id == turnId && it is ChatMessage.AssistantTurn) it.copy(streaming = false, toolRunning = false) else it
                    }
                } else {
                    // 什么都还没办成：这一回合连同半截字撤掉，那句话也撤下来、退回输入框 ——
                    // 气泡改不了，留在对话里没用；退回去改两个字就能重发。见 DESIGN.md 决策 6.3
                    val msgs = _messages.value
                    val asked = msgs.getOrNull(msgs.indexOfFirst { it.id == turnId } - 1) as? ChatMessage.UserText
                    val takeBackId = asked?.takeIf { stoppedByUser && it.text == text }?.id
                    _messages.value = msgs.filterNot { it.id == turnId || it.id == takeBackId }
                    if (takeBackId != null) restoredInput = text
                    session.discardLastTurn()
                }
                throw t
            } finally {
                aiBusy = false
                stoppedByUser = false
                pendingApproval = null
            }
        }
    }

    /** 卡片上的「记下」/「不要」 */
    fun answerApproval(approved: Boolean) {
        pendingApproval?.complete(approved)
        pendingApproval = null
    }

    /** 把一个事件叠到那个回合上。规则本身在 ChatMessage.kt，桌面速记也用同一份 */
    private fun patchTurn(turnId: Long, event: AgentEvent) {
        _messages.value = _messages.value.map { m ->
            if (m.id == turnId && m is ChatMessage.AssistantTurn) m.patched(event) else m
        }
    }

    /** 「停」。流被取消时 StreamResponse 会被 close 掉，见 ResponsesClient.step */
    fun stopStreaming() {
        stoppedByUser = true
        streamJob?.cancel()
        streamJob = null
    }

    fun consumeRestoredInput() {
        restoredInput = null
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

    // ---------------- 供应商配置（顶栏印章 → 纸签 → 配置页）----------------

    /** 存完下一句话就用新的；上一家的成绩不算到新一家头上，所以状态清空 */
    fun saveProvider(baseUrl: String, apiKey: String, model: String, thinking: Boolean) = viewModelScope.launch {
        ProviderStore.save(getApplication(), baseUrl, apiKey, model, thinking)
        ApiHealth.reset()
    }

    /** 恢复成打包时那份（secrets.properties） */
    fun resetProvider() = viewModelScope.launch {
        ProviderStore.clear(getApplication())
        ApiHealth.reset()
    }

    /** 测一下框里正在填的这份，不用先保存 */
    suspend fun testProvider(baseUrl: String, apiKey: String, model: String, thinking: Boolean): PingResult =
        ProviderTest.run(
            profile.value.copy(
                baseUrl = ProviderStore.normalizeBaseUrl(baseUrl),
                apiKey = apiKey.trim(),
                model = model.trim(),
                thinking = thinking
            )
        )

    // ---------------- 提醒 / 日志（列表、编辑、体检、投递日志共用）----------------

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

    fun setDayCheckTime(time: LocalTime) = viewModelScope.launch { DayTasks.setCheckTime(getApplication(), time) }

    /**
     * 手动建 / 改一条提醒 —— 逃生舱，必须能完全脱离 AI 用。见 DESIGN.md §05
     *
     * [dueDay] 不为 null 就是当天事项：[triggerAt] 不看，换成那天的收尾时刻，一律墙钟锚定。
     */
    fun upsertManual(
        id: Long?,
        title: String,
        note: String?,
        triggerAt: Long,
        rrule: String?,
        wallClockAnchored: Boolean,
        dueDay: LocalDate? = null
    ) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        if (dueDay != null) {
            val check = DayTasks.checkTime(getApplication())
            upsert(
                id, title, note, rrule,
                triggerAt = DayTasks.triggerFor(dueDay, check, zone, now),
                localTime = check.toString(), wallClockAnchored = true, dueDay = dueDay.toString(), now = now
            )
            return@launch
        }
        val localTime = if (wallClockAnchored) {
            java.time.Instant.ofEpochMilli(triggerAt).atZone(zone).toLocalTime()
                .withSecond(0).withNano(0).toString()
        } else null
        upsert(id, title, note, rrule, triggerAt, localTime, wallClockAnchored, dueDay = null, now = now)
    }

    private suspend fun upsert(
        id: Long?,
        title: String,
        note: String?,
        rrule: String?,
        triggerAt: Long,
        localTime: String?,
        wallClockAnchored: Boolean,
        dueDay: String?,
        now: Long
    ) {
        val zone = ZoneId.systemDefault()

        if (id == null) {
            val reminder = Reminder(
                title = title, note = note, rawInput = title,
                nextTriggerAt = triggerAt, rrule = rrule, zoneId = zone.id,
                localTime = localTime, wallClockAnchored = wallClockAnchored, dueDay = dueDay,
                createdAt = now, updatedAt = now
            )
            val newId = db.reminderDao().insert(reminder)
            db.reminderDao().byId(newId)?.let { rescheduler.schedule(it) }
        } else {
            val existing = db.reminderDao().byId(id) ?: return
            rescheduler.cancel(id)
            val updated = existing.copy(
                title = title, note = note, nextTriggerAt = triggerAt, rrule = rrule,
                localTime = localTime, wallClockAnchored = wallClockAnchored, dueDay = dueDay,
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
        // 当天事项有自己的「完成」：重复的只算今天这一次。见 DayTasks.complete
        if (DayTasks.complete(getApplication(), r)) return@launch
        rescheduler.cancel(r.id)
        Notifier.cancel(getApplication(), r.id)
        db.reminderDao().setStatus(r.id, ReminderStatus.DONE, System.currentTimeMillis())
    }

    fun delete(r: Reminder) = viewModelScope.launch {
        rescheduler.cancel(r.id)
        Notifier.cancel(getApplication(), r.id)
        db.reminderDao().delete(r)
    }

    /**
     * 列表页的「撤销」：把完成 / 删除之前那一整条原样放回去。
     * 删除是当场真删的（不是等撤销过期再删）—— 进程在这几秒里被杀，结果也只是「删了」，
     * 不会留下一条库里有、闹钟没有的记录。放回来之后照常排闹钟；已经过点的交给巡检补发。
     */
    fun restore(r: Reminder) = viewModelScope.launch {
        if (db.reminderDao().byId(r.id) != null) db.reminderDao().update(r) else db.reminderDao().insert(r)
        if (r.status == ReminderStatus.SCHEDULED && r.nextTriggerAt > System.currentTimeMillis()) {
            rescheduler.schedule(r)
        }
    }

    fun rescheduleAll() = viewModelScope.launch { rescheduler.rescheduleAll() }

    fun clearLogs() = viewModelScope.launch { db.fireLogDao().clear() }

    /** 某条提醒当前是否真的有闹钟排着 —— 直接问 AlarmManager，不看数据库 */
    fun isArmed(id: Long): Boolean = rescheduler.isScheduled(id)
}
