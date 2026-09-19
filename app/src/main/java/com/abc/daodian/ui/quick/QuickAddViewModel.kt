package com.abc.daodian.ui.quick

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.harness.AgentEvent
import com.abc.daodian.harness.Item
import com.abc.daodian.harness.Session
import com.abc.daodian.harness.permission.Approval
import com.abc.daodian.harness.permission.PermissionGate
import com.abc.daodian.harness.permission.PermissionStore
import com.abc.daodian.harness.provider.ApiHealth
import com.abc.daodian.harness.provider.ProviderProfile
import com.abc.daodian.harness.provider.ProviderStore
import com.abc.daodian.ui.Agents
import com.abc.daodian.ui.chat.ChatMessage
import com.abc.daodian.ui.chat.patched
import com.abc.daodian.widget.WidgetUpdater
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 桌面速记：从小组件右下角那枚墨印拉起来的一小张纸。见 DESIGN.md §8.3
 *
 * **只有语音。** 要聊天、要改字，回 app 的对话页 —— 这里不做第二个输入框。
 * 其余和对话页是同一套东西的缩小版：同一个 agent（[Agents]）、同一套回合规则（[patched]）、
 * 同一个授权模式。只摆最近一问一答，不留对话流。
 */
class QuickAddViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * 和对话页同一份配置（[ProviderStore]）：在 app 里改完，桌面速记跟着换。
     * 每次真要说话时才去读 —— 这张纸是从桌面直接拉起来的，构造函数里抢读会跟第一句话赛跑。
     */
    private suspend fun profile(): ProviderProfile = ProviderStore.flow(getApplication()).first()
    private val voice = VoiceInput(app)

    val voiceAvailable: Boolean get() = voice.available

    var listening by mutableStateOf(false)
        private set
    /** 边说边出来的字 */
    var heard by mutableStateOf("")
        private set
    /** 麦克风音量，0..1 */
    var level by mutableFloatStateOf(0f)
        private set
    /** 印熄下来时替代提示语的那句话：没听清、停了、服务连不上…… */
    var note by mutableStateOf<String?>(null)
        private set
    /** 语音压根用不了（没有识别服务 / 没给麦克风权限）：纸上只剩原因和「去 app 里说」 */
    var blocked by mutableStateOf<String?>(null)
        private set

    /** 最近一句送去解析的话，和它换来的那个回合 */
    var asked by mutableStateOf<String?>(null)
        private set
    var turn by mutableStateOf<ChatMessage.AssistantTurn?>(null)
        private set
    var aiBusy by mutableStateOf(false)
        private set
    /** 刚建好的那条。非空之后纸停一会儿自己缩回去 */
    var savedId by mutableStateOf<Long?>(null)
        private set

    /** 喂给下一轮的历史。模型反问之后接着答，得看得见上文 */
    private val session = Session()
    private var turnIds = 0L
    private var job: Job? = null
    private var stoppedByUser = false
    /** 等你点头的那次调用。非空 = 纸上亮着授权条 */
    var approvalCall by mutableStateOf<Item.ToolCall?>(null)
        private set

    /** 授权条亮着时，循环挂在这上面等 [answerApproval] */
    private var pendingApproval: CompletableDeferred<Approval>? = null

    /** 这一轮你点了「不」：模型回一句就完，不再自动接着听 */
    private var deniedThisTurn = false

    fun startListening() {
        if (aiBusy || savedId != null) return
        QuickTrace.log(getApplication(), "vm startListening")
        blocked = null
        note = null
        heard = ""
        listening = true
        voice.start { event ->
            when (event) {
                VoiceInput.Event.Ready -> Unit
                is VoiceInput.Event.Partial -> heard = event.text
                is VoiceInput.Event.Level -> level = event.value
                is VoiceInput.Event.Final -> {
                    val said = event.text.ifBlank { heard }
                    endListening()
                    if (said.isBlank()) note = "没听清，再说一次？" else send(said)
                }
                is VoiceInput.Event.Error -> {
                    val partial = heard
                    endListening()
                    // 说到一半出错（多半是停顿超时）：已经听到的字就当说完了，别让人从头再说一遍
                    if (partial.isNotBlank()) send(partial) else note = event.message
                }
            }
        }
    }

    /** 点了印：说完了。结果随后从 Final 回来 */
    fun finishListening() = voice.stop()

    fun blockVoice(reason: String) {
        endListening()
        blocked = reason
    }

    private fun send(text: String) {
        val said = text.trim()
        if (said.isBlank() || aiBusy) return
        asked = said
        note = null
        job = viewModelScope.launch {
            var t = ChatMessage.AssistantTurn(turnIds++, streaming = true)
            turn = t
            aiBusy = true
            val app = getApplication<Application>()
            deniedThisTurn = false
            val gate = PermissionGate(PermissionStore.flow(app).first()) { call, _ ->
                approvalCall = call
                try {
                    CompletableDeferred<Approval>().also { pendingApproval = it }.await()
                } finally {
                    approvalCall = null
                }
            }
            var askBack = false
            try {
                Agents.of(app, profile()).run(session, said, ZonedDateTime.now(), gate).collect { event ->
                    t = t.patched(event)
                    turn = t
                    if (event is AgentEvent.Finished || event is AgentEvent.Failed) ApiHealth.record(event)
                }
                val reminderId = t.reminderId
                if (reminderId != null) {
                    savedId = reminderId
                    // app 多半压根没开着，MainViewModel 盯的那条 observeAll 兜不到这里 —— 自己喊
                    WidgetUpdater.announce(app, reminderId)
                } else {
                    // 没建成也没出错 = 模型在反问（或闸门要确认）。纯语音就该一路说下去：自动接着听
                    askBack = !t.isError && !deniedThisTurn
                }
            } catch (c: CancellationException) {
                if (t.reminderId == null) {
                    turn = null
                    asked = null
                    session.discardLastTurn()
                    if (stoppedByUser) note = "停了。点一下，重说"
                } else {
                    turn = t.copy(streaming = false, toolRunning = false)
                }
                throw c
            } finally {
                aiBusy = false
                stoppedByUser = false
                pendingApproval = null
            }
            if (askBack) startListening()
        }
    }

    /** 授权条上的「好」/「不」 */
    fun answerApproval(approved: Boolean) {
        if (!approved) deniedThisTurn = true
        pendingApproval?.complete(if (approved) Approval.Approved else Approval.Denied)
        pendingApproval = null
    }

    /** 失败后重说同一句：失败那一轮从模型的记录里拿掉，理由同 MainViewModel.retryLast */
    fun retry() {
        val said = asked ?: return
        session.discardLastTurn()
        send(said)
    }

    /** 「停」：掐断这条流，纸回到待命 */
    fun stop() {
        stoppedByUser = true
        job?.cancel()
        job = null
    }

    /** 整张纸要走了：收音和流一起停 */
    fun stopAll() {
        QuickTrace.log(getApplication(), "vm stopAll")
        endListening()
        job?.cancel()
        job = null
    }

    fun toggleReasoning() {
        turn = turn?.let { it.copy(reasoningOpen = !it.reasoningOpen) }
    }

    private fun endListening() {
        if (listening) QuickTrace.log(getApplication(), "vm endListening heard='$heard' note=$note")
        voice.cancel()
        listening = false
        level = 0f
        heard = ""
    }

    override fun onCleared() {
        voice.release()
    }
}
