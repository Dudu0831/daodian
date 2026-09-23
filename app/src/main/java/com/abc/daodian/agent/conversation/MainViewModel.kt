package com.abc.daodian.agent.conversation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.ask.AskAnswer
import com.abc.daodian.agent.engine.ask.AskRequest
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.ask.Asker
import com.abc.daodian.agent.engine.ask.Pick
import com.abc.daodian.agent.model.provider.ApiHealth
import com.abc.daodian.agent.model.provider.PingResult
import com.abc.daodian.agent.model.provider.ProviderProfile
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.agent.model.provider.ProviderTest
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.agent.conversation.data.ChatStore
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.reconciliation.LedgerCheck
import com.abc.daodian.ledger.reconciliation.LedgerCheckPrompt
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.scheduling.DayTasks
import com.abc.daodian.reminder.scheduling.Rescheduler
import com.abc.daodian.agent.conversation.answered
import com.abc.daodian.agent.conversation.patched
import com.abc.daodian.agent.conversation.restoredMessages
import com.abc.daodian.agent.conversation.scopeLabelOf
import com.abc.daodian.agent.conversation.toggleTrace
import com.abc.daodian.agent.conversation.withAsk
import com.abc.daodian.reminder.widget.WidgetUpdater
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = ReminderDatabase.get(app)
    private val rescheduler = Rescheduler(app)

    /**
     * 供应商配置。存在机器上、随时可改（[ProviderStore]），所以是一条流，不是一个常量 ——
     * 顶栏那枚印、桌面速记、下一句话用哪个模型，都从这里取当时的值。
     */
    val profile = ProviderStore.flow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProviderStore.seed)

    /** 上一次调用顺不顺，给印和纸签用。见 [ApiHealth] */
    val apiState = ApiHealth.state

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
        val session = Session(store.load(), store)
        // app 在等你答问卡的时候被杀了：那次调用没拿到结果，补上「没答」—— 缺一个下一次请求就会被网关拒掉
        session.closeDanglingCalls { if (it.name == AskUserTool.NAME) AskUserTool.UNANSWERED else AgentLoop.ABORTED }
        _messages.value = restoredMessages(session.turns, ::newId) + _messages.value
        session
    }

    var aiBusy by mutableStateOf(false)
        private set

    /** 点了「停」之后要退回输入框的原话。界面取走后调 [consumeRestoredInput] */
    var restoredInput by mutableStateOf<String?>(null)
        private set

    /** 最后一句用户说的话，失败后「重试」用得上 */
    private var lastUserInput: String? = null

    /** 上一轮是 app 自己发起的（对账）：重试时照样按自动发起的一轮重发 */
    private var lastTrigger = false

    /** 当前那一轮。「停」靠它掐断 */
    private var streamJob: Job? = null

    /** 这次取消是用户按的「停」，不是离开页面之类 */
    private var stoppedByUser = false

    /** 在等你答的那张问卡：循环挂在 [answer] 上，直到你点完、说完 */
    private class PendingAsk(
        val turnId: Long,
        val callId: String,
        val request: AskRequest,
        val answer: CompletableDeferred<AskAnswer>
    )

    private var pendingAsk: PendingAsk? = null

    /** 非空 = 对话里有张问卡在等你。输入框里发出去的话都交给它（见 [sendMessage]） */
    var asking by mutableStateOf<AskRequest?>(null)
        private set

    /** 点了哪一题的「其他…」。非空时输入框句首垫着 [askScope]，发出去只算这一题 */
    private var askScopeIndex: Int? = null
    var askScope by mutableStateOf<String?>(null)
        private set

    /**
     * 一句话 → agent 循环。见 DESIGN.md §6.8
     *
     * 写操作（建提醒、改账）在循环里直接办，对话里留一道痕；拿不准时模型自己出问卡（§6.9）。
     * 问卡等着的时候，这里发出去的话是给问卡的：点了「其他…」就只答那一题，否则算直接说。
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (pendingAsk != null) {
            if (askScopeIndex != null) fillAsk(trimmed) else sayToAsk(trimmed)
            return
        }
        if (aiBusy) return

        lastUserInput = trimmed
        lastTrigger = false
        _messages.value = _messages.value + ChatMessage.UserText(newId(), trimmed)
        runTurn(trimmed)
    }

    /**
     * 每晚对账（通知上点「现在」、记账页点「现在就说」）：app 自己开一轮，把没认出来的几笔列给模型，
     * 由它在对话里一笔笔问你。这一轮画成一条分隔线，不是你的气泡。见 LEDGER_PLAN.md §4 ③
     */
    fun startLedgerCheck() = viewModelScope.launch {
        val app = getApplication<Application>()
        LedgerCheck.done(app)
        if (aiBusy) return@launch
        val text = LedgerCheckPrompt.build(LedgerStore.get(app))
            ?: run {
                _messages.value = _messages.value + ChatMessage.AssistantTurn(
                    newId(), blocks = listOf(TurnBlock.Prose("p0", 0, "账都对上了，没有要问你的。")), idle = false
                )
                return@launch
            }
        _messages.value = _messages.value + ChatMessage.UserText(newId(), text, trigger = true)
        lastUserInput = text
        lastTrigger = true
        runTurn(text, trigger = true)
    }

    /** 从记账页「这笔不对？去对话里说」过来：把开头替你写好，放进输入框 */
    fun prefill(text: String) {
        restoredInput = text
    }

    /**
     * 失败后的「重试」。失败那一整个回合会被抹掉，模型那边的记录也一起拿掉 ——
     * 不然下一轮模型会看到自己上一次跑偏的痕迹，顺着往下编。
     */
    fun retryLast() {
        val trimmed = lastUserInput ?: return
        if (aiBusy) return
        _messages.value = _messages.value.filterNot { it is ChatMessage.AssistantTurn && it.isError }
        runTurn(trimmed, retry = true, trigger = lastTrigger)
    }

    /**
     * 跑一轮。一个回合**原地长大**：正文、痕、问卡按到货顺序摞，中途不换消息类型。见 DESIGN.md §6.7
     */
    private fun runTurn(text: String, retry: Boolean = false, trigger: Boolean = false) {
        streamJob = viewModelScope.launch {
            val session = session.await()
            if (retry) session.discardLastTurn()
            val turnId = newId()
            _messages.value = _messages.value + ChatMessage.AssistantTurn(turnId, streaming = true)
            aiBusy = true

            val asker = Asker { call, request ->
                val pending = PendingAsk(turnId, call.callId, request, CompletableDeferred())
                pendingAsk = pending
                asking = request
                patchTurnWith(turnId) { t ->
                    t.withAsk(call.callId) { it.copy(request = request, state = AskState.READY, picks = List(request.questions.size) { null }) }
                }
                try {
                    pending.answer.await()
                } finally {
                    if (pendingAsk === pending) pendingAsk = null
                    asking = null
                    askScopeIndex = null
                    askScope = null
                }
            }
            try {
                Agents.of(getApplication(), profile.value)
                    .run(session, text, ZonedDateTime.now(), asker, trigger)
                    .collect { event ->
                        patchTurn(turnId, event)
                        if (event is AgentEvent.Finished || event is AgentEvent.Failed) ApiHealth.record(event)
                    }
            } catch (t: CancellationException) {
                val turn = _messages.value.firstOrNull { it.id == turnId } as? ChatMessage.AssistantTurn
                if (turn != null && (turn.committed || turn.blocks.any { it is TurnBlock.Ask })) {
                    // 已经办成了点什么、或者问过你：回合留着（痕和答案都是真的），只是不再往下长；
                    // 没答完的问卡画成「没答」—— 循环那边也给它记了「没答」
                    _messages.value = _messages.value.map { m ->
                        if (m.id == turnId && m is ChatMessage.AssistantTurn) m.copy(
                            streaming = false,
                            blocks = m.blocks.map {
                                if (it is TurnBlock.Ask && (it.state == AskState.READY || it.state == AskState.DRAFT)) it.copy(state = AskState.UNANSWERED) else it
                            }
                        ) else m
                    }
                } else {
                    // 什么都还没办成：这一回合连同半截字撤掉，那句话也撤下来、退回输入框 ——
                    // 气泡改不了，留在对话里没用；退回去改两个字就能重发。见 DESIGN.md 决策 6.3
                    val msgs = _messages.value
                    val asked = msgs.getOrNull(msgs.indexOfFirst { it.id == turnId } - 1) as? ChatMessage.UserText
                    val takeBackId = asked?.takeIf { stoppedByUser && it.text == text }?.id
                    // app 自己发起的那一轮被停掉：分隔线一起撤，但不往输入框里退 —— 那句话不是你说的
                    val trig = asked?.takeIf { it.trigger && it.text == text }?.id
                    if (trig != null) {
                        _messages.value = msgs.filterNot { it.id == turnId || it.id == trig }
                        session.discardLastTurn()
                        throw t
                    }
                    _messages.value = msgs.filterNot { it.id == turnId || it.id == takeBackId }
                    if (takeBackId != null) restoredInput = text
                    session.discardLastTurn()
                }
                throw t
            } finally {
                aiBusy = false
                stoppedByUser = false
                pendingAsk = null
            }
        }
    }

    // ---------------- 问卡（DESIGN.md §6.9）----------------

    /** 点了一颗猜测。再点同一颗是取消。只有一题的卡点了就算答：停一下让你看清点的是哪个，再收起 */
    fun pickAsk(callId: String, question: Int, option: Int) {
        val pending = pendingAsk?.takeIf { it.callId == callId } ?: return
        var picked: Pick? = null
        patchTurnWith(pending.turnId) { t ->
            t.withAsk(callId) { a ->
                val now = a.picks.getOrNull(question)
                picked = if (now == Pick.Option(option)) null else Pick.Option(option)
                a.copy(picks = a.picks.toMutableList().also { it[question] = picked }, editing = a.editing?.takeIf { it != question })
            }
        }
        if (askScopeIndex == question) clearScope()
        if (pending.request.single && picked != null) viewModelScope.launch {
            delay(SINGLE_PICK_HOLD_MS)
            if (pendingAsk === pending) submitAsk(callId)
        }
    }

    /** 点了某一题的「其他…」：借用输入框，句首垫上那一题。再点一次收回 */
    fun otherAsk(callId: String, question: Int) {
        val pending = pendingAsk?.takeIf { it.callId == callId } ?: return
        val on = askScopeIndex != question
        askScopeIndex = if (on) question else null
        askScope = if (on) pending.request.questions.getOrNull(question)?.let(::scopeLabelOf) else null
        patchTurnWith(pending.turnId) { t -> t.withAsk(callId) { it.copy(editing = if (on) question else null) } }
    }

    /** 「就这样」/「都先放着」 */
    fun submitAsk(callId: String) {
        val pending = pendingAsk?.takeIf { it.callId == callId } ?: return
        val block = (_messages.value.firstOrNull { it.id == pending.turnId } as? ChatMessage.AssistantTurn)
            ?.blocks?.firstOrNull { it is TurnBlock.Ask && it.callId == callId } as? TurnBlock.Ask ?: return
        answerAsk(pending, AskAnswer.Picked(block.picks))
    }

    /** 「其他…」那一题，在输入框里写的答案：落回那一题，卡片还等着你 */
    private fun fillAsk(text: String) {
        val pending = pendingAsk ?: return
        val q = askScopeIndex ?: return
        clearScope()
        patchTurnWith(pending.turnId) { t ->
            t.withAsk(pending.callId) { a ->
                a.copy(picks = a.picks.toMutableList().also { it[q] = Pick.Typed(text) }, editing = null)
            }
        }
        if (pending.request.single) submitAsk(pending.callId)
    }

    /** 没点，直接说了一句：给整张卡的，模型拿原话去对 */
    private fun sayToAsk(text: String) {
        val pending = pendingAsk ?: return
        answerAsk(pending, AskAnswer.Said(text))
    }

    private fun answerAsk(pending: PendingAsk, answer: AskAnswer) {
        pendingAsk = null
        patchTurnWith(pending.turnId) { it.answered(pending.callId, answer) }
        pending.answer.complete(answer)
    }

    private fun clearScope() {
        askScopeIndex = null
        askScope = null
    }

    /** 把一个事件叠到那个回合上。规则本身在 ChatMessage.kt，桌面速记也用同一份 */
    private fun patchTurn(turnId: Long, event: AgentEvent) = patchTurnWith(turnId) { it.patched(event) }

    private fun patchTurnWith(turnId: Long, change: (ChatMessage.AssistantTurn) -> ChatMessage.AssistantTurn) {
        _messages.value = _messages.value.map { m ->
            if (m.id == turnId && m is ChatMessage.AssistantTurn) change(m) else m
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

    fun toggleTrace(turnId: Long, callId: String) = patchTurnWith(turnId) { it.toggleTrace(callId) }

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

    private companion object {
        /** 只有一题的卡，点了之后停这么久再收起：让你看清点的是哪个（动效稿「点选」那一拍） */
        const val SINGLE_PICK_HOLD_MS = 560L
    }
}
