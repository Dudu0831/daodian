package com.abc.daodian.agent.conversation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abc.daodian.agent.conversation.data.ChatStore
import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.ask.AskAnswer
import com.abc.daodian.agent.engine.ask.AskRequest
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.ask.Asker
import com.abc.daodian.agent.engine.ask.Pick
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.feature.Trigger
import com.abc.daodian.agent.model.provider.ApiHealth
import com.abc.daodian.agent.model.provider.PingResult
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.agent.model.provider.ProviderTest
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
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

/**
 * 对话页（和顶栏印章、模型配置页）的状态。见 DESIGN.md §06
 *
 * 只管对话：说一句、问卡、停、重试、app 发起的一轮、模型配置。提醒、账的页面各有自己的 ViewModel。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * 供应商配置。存在机器上、随时可改（[ProviderStore]），所以是一条流，不是一个常量 ——
     * 顶栏那枚印、桌面速记、下一句话用哪个模型，都从这里取当时的值。
     */
    val profile = ProviderStore.flow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProviderStore.seed)

    /** 上一次调用顺不顺，给印和纸签用。见 [ApiHealth] */
    val apiState = ApiHealth.state

    /** 后台正在跑的 agent：印外面转一圈细线 */
    val running = AgentActivity.running

    /** 会在对话里留痕的工具（写操作）。模块清单启动时就装好了，取一次就够 */
    private val traced by lazy { ChatAgent.traced(app) }

    // ---------------- 对话 ----------------

    private val idGen = AtomicLong(0)
    private fun newId() = idGen.getAndIncrement()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _restored = MutableStateFlow(false)

    /**
     * 历史读回来、画面重建好了没有（读坏了也算，不能让界面一直等）。读完之前 [messages] 是空的，
     * 但那不是「没聊过」—— 对话页这时不画空状态，开屏也先不撤（MainActivity），不然会先闪一下招呼语再换成聊天记录。
     */
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    /**
     * 喂给模型的历史（完整结构），落盘在 `conversation/data/`，重启接着聊。
     * [_messages] 是画面、这里是模型看到的，两者各管各的；启动时画面从它重建一次。
     * 读库要一会儿，用到它的地方都先 await —— 读完之前说的第一句话也不会丢上文。
     */
    private val session: Deferred<Session> = viewModelScope.async {
        try {
            val store = ChatStore.get(app)
            val session = Session(store.load(), store)
            // app 在等你答问卡的时候被杀了：那次调用没拿到结果，补上「没答」—— 缺一个下一次请求就会被网关拒掉
            session.closeDanglingCalls { if (it.name == AskUserTool.NAME) AskUserTool.UNANSWERED else AgentLoop.ABORTED }
            _messages.value = restoredMessages(session.turns, traced, ::newId) + _messages.value
            session
        } finally {
            _restored.value = true
        }
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
     * 一句话 → agent 循环。见 DESIGN.md §6.1
     *
     * 写操作（建提醒、改账）在循环里直接办，对话里留一道痕；拿不准时模型自己出问卡（§6.6）。
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
     * app 自己开一轮（比如每晚对账：通知上点「现在」、记账页点「现在就说」）。开场白由模块给
     * （[com.abc.daodian.agent.feature.Feature.trigger]），画成一条分隔线，不是你的气泡。
     * [key] 形如 `ledger:check`。
     */
    fun startTrigger(key: String) = viewModelScope.launch {
        val trigger = FeatureRegistry.trigger(getApplication(), key) ?: return@launch
        if (aiBusy) return@launch
        when (trigger) {
            is Trigger.Note -> _messages.value = _messages.value + ChatMessage.AssistantTurn(
                newId(), blocks = listOf(TurnBlock.Prose("p0", 0, trigger.text)), idle = false
            )
            is Trigger.Turn -> {
                _messages.value = _messages.value + ChatMessage.UserText(newId(), trigger.text, trigger = true)
                lastUserInput = trigger.text
                lastTrigger = true
                runTurn(trigger.text, trigger = true)
            }
        }
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
     * 跑一轮。一个回合**原地长大**：正文、痕、问卡按到货顺序摞，中途不换消息类型。见 DESIGN.md §6.5
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
                ChatAgent.of(getApplication(), profile.value)
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
                    // 气泡改不了，留在对话里没用；退回去改两个字就能重发。见 DESIGN.md §6.1「喊停与重试」
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

    // ---------------- 问卡（DESIGN.md §6.6）----------------

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
    private fun patchTurn(turnId: Long, event: AgentEvent) = patchTurnWith(turnId) { it.patched(event, traced) }

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

    /** 测一下框里正在填的这份，不用先保存。带的是和真实请求一样的 system 和工具 */
    suspend fun testProvider(baseUrl: String, apiKey: String, model: String, thinking: Boolean): PingResult =
        ProviderTest.run(
            profile.value.copy(
                baseUrl = ProviderStore.normalizeBaseUrl(baseUrl),
                apiKey = apiKey.trim(),
                model = model.trim(),
                thinking = thinking
            ),
            system = ChatAgent.system,
            tools = ChatAgent.tools(getApplication())
        )

    private companion object {
        /** 只有一题的卡，点了之后停这么久再收起：让你看清点的是哪个（动效稿「点选」那一拍） */
        const val SINGLE_PICK_HOLD_MS = 560L
    }
}
