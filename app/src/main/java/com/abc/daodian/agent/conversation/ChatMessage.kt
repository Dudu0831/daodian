package com.abc.daodian.agent.conversation

import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.StopReason
import com.abc.daodian.agent.engine.Turn
import com.abc.daodian.agent.engine.ask.AskAnswer
import com.abc.daodian.agent.engine.ask.AskRequest
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.ask.Pick
import com.abc.daodian.agent.feature.TraceState
import com.abc.daodian.agent.model.LlmEvent

/**
 * 对话流里的一条消息。见 DESIGN.md §6.6（问卡与痕），动效稿的链接也在那一节。
 *
 * 只有两类：用户说的话，和助手的一个回合。
 * 助手回合是**原地长大**的：一个回合里 agent 可能调好几次模型（调工具 → 看结果 → 再说话），
 * 长出来的东西按到货顺序摞成一串块（[TurnBlock]），中途不换消息类型、不清屏。
 *
 * 这里只是画面的状态；喂给模型的历史在 [com.abc.daodian.agent.engine.Session] 里，两者各管各的。
 */
sealed interface ChatMessage {
    val id: Long

    data class UserText(
        override val id: Long,
        val text: String,
        /** 气泡只在刚发出时从输入框升起一次，列表滚回来重组时不再升 */
        val sentAt: Long = System.currentTimeMillis(),
        /** app 自己发起的一轮（每晚对账）：画成一条分隔线，不是气泡 —— 这句话用户没说过 */
        val trigger: Boolean = false
    ) : ChatMessage

    data class AssistantTurn(
        override val id: Long,
        /** 推理模型的思考过程。普通模型压根不发，这里就一直是空的 */
        val reasoning: String = "",
        val reasoningOpen: Boolean = false,
        /** 思考了多久。第一块内容一到就定格；null = 还在想，或者压根没思考 */
        val thoughtMillis: Long? = null,
        /** 正文 / 痕 / 问卡 / 你直接说的话，按到货顺序 */
        val blocks: List<TurnBlock> = emptyList(),
        /** 连不上，挂「手动填一条 / 重试」 */
        val isError: Boolean = false,
        /** 这一轮还没跑完 */
        val streaming: Boolean = false,
        /**
         * 模型那边眼下没东西在出：刚发出去、工具跑完了等它接着说、问卡答完等它接着办。
         * 还在跑的时候据此在末尾画墨条 —— 空着不动比墨条更让人发懵
         */
        val idle: Boolean = true,
        /** 流式没走通，正在走一次性请求 —— 墨条上方挂一行小字 */
        val fellBack: Boolean = false,
        /** 当前是这一轮的第几次模型调用。回退时只擦这一步长出来的块 */
        val step: Int = 0,
        val startedAt: Long = System.currentTimeMillis()
    ) : ChatMessage {

        /** 思考块折成一行「想了 N 秒」：思考流完（后面的内容来了），或者整个回合结束 */
        val reasoningFolded: Boolean
            get() = !streaming || thoughtMillis != null

        /** 末尾要不要画墨条。问卡在等你答的时候不画 —— 是它在等你，不是你在等它 */
        val waiting: Boolean
            get() = streaming && idle && blocks.none { it is TurnBlock.Ask && it.state == AskState.READY }

        /** 已经办成了点什么（落了库，或者你答了问卡）：失败了也不能整轮重跑，会办两遍 */
        val committed: Boolean
            get() = blocks.any {
                (it is TurnBlock.Trace && it.state == TraceState.OK) ||
                    (it is TurnBlock.Ask && it.state in ANSWERED_STATES)
            }
    }
}

/** 问卡的状态，见 DESIGN.md §6.6「问卡的规矩」 */
enum class AskState {
    /** 参数还在流：收全的题先画出来，还不能点 */
    DRAFT,
    /** 等你答 */
    READY,
    /** 点完了（或者一题的卡点了一颗） */
    ANSWERED,
    /** 没点，直接说了一句 */
    SAID,
    /** 没答：叫停了，或者 app 被杀了 */
    UNANSWERED
}

private val ANSWERED_STATES = setOf(AskState.ANSWERED, AskState.SAID)

/** 回合里的一块。[step] 是它在第几次模型调用里长出来的 —— 流式回退时这一步的都擦掉 */
sealed interface TurnBlock {
    val key: String
    val step: Int

    /** 模型说的话 */
    data class Prose(override val key: String, override val step: Int, val text: String) : TurnBlock

    /** 一次写操作留下的痕：一行小字，不是卡片。见 TraceLine.kt */
    data class Trace(
        val callId: String,
        val tool: String,
        override val step: Int,
        /** 参数原文。流着的时候是半截，[AgentEvent.ToolStarting] 时换成完整的 */
        val arguments: String = "",
        val state: TraceState = TraceState.RUNNING,
        /** 工具回给模型的话；办成了从里面抠要显示的，没办成从里面抠原因 */
        val output: String? = null,
        /** 办成后指向的记录（提醒 id、流水 id），点痕去那儿 */
        val ref: Long? = null,
        /** 办成的时刻。对勾只在刚办成时描一次 */
        val doneAt: Long = 0,
        val expanded: Boolean = false
    ) : TurnBlock {
        override val key: String get() = callId
    }

    /** 一张问卡。见 AskCard.kt */
    data class Ask(
        val callId: String,
        override val step: Int,
        val arguments: String = "",
        /** 参数收全、交到你手上时才有；之前按 [arguments] 画草稿 */
        val request: AskRequest? = null,
        val state: AskState = AskState.DRAFT,
        /** 每题点的是哪个，和 request.questions 一一对应 */
        val picks: List<Pick?> = emptyList(),
        /** 正在用输入框填「其他…」的那一题 */
        val editing: Int? = null,
        /** 「答」那枚印落下的时刻。只在刚答时盖一次 */
        val answeredAt: Long = 0
    ) : TurnBlock {
        override val key: String get() = callId
    }

    /** 问卡等着时，你没点、直接在输入框里说的那句话 —— 画成你的气泡，后面的回应另起一个「· 到点」 */
    data class Said(override val key: String, override val step: Int, val text: String, val sentAt: Long) : TurnBlock
}

/**
 * 要画出来的块。模型把参数写坏、紧接着自己改好重来的那一次不画 —— 后面那道痕已经说明了结果，
 * 留一个红 × 只会让人以为出了事。中间隔着问卡的不算（比如时间过了、转成问你），那个 × 是在解释为什么要问。
 */
val ChatMessage.AssistantTurn.visibleBlocks: List<TurnBlock>
    get() = blocks.filterIndexed { i, b ->
        if (b !is TurnBlock.Trace || b.state != TraceState.FAILED) return@filterIndexed true
        val after = blocks.drop(i + 1)
        val retry = after.indexOfFirst { it is TurnBlock.Trace && it.tool == b.tool }
        retry < 0 || after.take(retry).any { it is TurnBlock.Ask }
    }

/*
 * 一个回合怎么随着 agent 的事件长大。对话页（ChatViewModel）和桌面速记（entry/quick）共用 ——
 * 同一句话在两处长得一模一样，靠的就是这几条规则只有一份。
 * 问卡交到你手上、你答了，这两步不走事件，由界面那边直接改（见 [withAsk]）。
 *
 * [traced] 是会留痕的工具名（写操作，见 [ChatAgent.traced]）。只读的（查账）不留，问人的画问卡。
 */

/** 把一个事件叠到这个回合上 */
fun ChatMessage.AssistantTurn.patched(event: AgentEvent, traced: Set<String>): ChatMessage.AssistantTurn = when (event) {
    is AgentEvent.Model -> enteringStep(event.step).patchedModel(event.event, traced)
    is AgentEvent.ToolStarting -> startingTool(event.call, traced)
    is AgentEvent.ToolFinished -> finishingTool(event, traced)
    is AgentEvent.Finished -> copy(
        streaming = false,
        blocks = if (event.stop == StopReason.STEP_LIMIT && blocks.none { it is TurnBlock.Prose }) {
            // 撞到步数上限多半是模型在原地打转，一句话都没留下时替它交代一声
            blocks + TurnBlock.Prose("limit", step, "绕了几圈也没办成，换个说法试试？")
        } else blocks
    ).settleThought()
    // 已经办成了点什么、收尾那步才断：不挂「重试」—— 重试等于把这句话再办一遍
    is AgentEvent.Failed -> if (committed) copy(
        streaming = false,
        blocks = blocks.filterNot { it.step == step && it is TurnBlock.Prose } +
            TurnBlock.Prose("broken", step, "后面的话没说完就断了。")
    ).settleThought() else copy(
        // 第二句「你可以自己填一条」由 UI 补，见 AssistantTurnRow
        streaming = false, isError = true,
        blocks = listOf(TurnBlock.Prose("error", step, "连不上服务器，这句话没能解析。"))
    ).settleThought()
}

/** 改某张问卡（不在就原样返回） */
fun ChatMessage.AssistantTurn.withAsk(callId: String, change: (TurnBlock.Ask) -> TurnBlock.Ask): ChatMessage.AssistantTurn =
    copy(blocks = blocks.map { if (it is TurnBlock.Ask && it.callId == callId) change(it) else it })

/** 你答了问卡：模型接下来要接着办，末尾重新画墨条 */
fun ChatMessage.AssistantTurn.answered(callId: String, answer: AskAnswer): ChatMessage.AssistantTurn {
    val now = System.currentTimeMillis()
    val next = withAsk(callId) {
        when (answer) {
            is AskAnswer.Picked -> it.copy(state = AskState.ANSWERED, picks = answer.picks, editing = null, answeredAt = now)
            is AskAnswer.Said -> it.copy(state = AskState.SAID, editing = null, answeredAt = now)
            AskAnswer.Unanswered -> it.copy(state = AskState.UNANSWERED, editing = null)
        }
    }
    return when (answer) {
        is AskAnswer.Said -> next.copy(blocks = next.blocks + TurnBlock.Said("said-$callId", step, answer.text, now), idle = true)
        else -> next.copy(idle = true)
    }
}

fun ChatMessage.AssistantTurn.toggleTrace(callId: String): ChatMessage.AssistantTurn =
    copy(blocks = blocks.map { if (it is TurnBlock.Trace && it.callId == callId) it.copy(expanded = !it.expanded) else it })

/** 进入新的一步 */
private fun ChatMessage.AssistantTurn.enteringStep(step: Int) =
    if (step == this.step) this else copy(step = step)

private fun ChatMessage.AssistantTurn.patchedModel(event: LlmEvent, traced: Set<String>): ChatMessage.AssistantTurn = when (event) {
    is LlmEvent.Reasoning -> copy(reasoning = reasoning + event.delta, idle = false)
    is LlmEvent.Text -> {
        val last = blocks.lastOrNull()
        val merged = if (last is TurnBlock.Prose && last.step == step) {
            blocks.dropLast(1) + last.copy(text = last.text + event.delta)
        } else {
            blocks + TurnBlock.Prose("p${blocks.size}-$step", step, event.delta)
        }
        copy(blocks = merged, idle = false).settleThought()
    }
    is LlmEvent.ToolStarted -> when {
        event.name == AskUserTool.NAME -> copy(blocks = blocks + TurnBlock.Ask(event.callId, step), idle = false).settleThought()
        event.name in traced -> copy(blocks = blocks + TurnBlock.Trace(event.callId, event.name, step), idle = false).settleThought()
        // 只读的工具（查账）不留痕：它在跑的时候照样画墨条
        else -> copy(idle = true).settleThought()
    }
    is LlmEvent.ToolArgs -> copy(blocks = blocks.map {
        when {
            it is TurnBlock.Trace && it.callId == event.callId -> it.copy(arguments = it.arguments + event.delta)
            it is TurnBlock.Ask && it.callId == event.callId -> it.copy(arguments = it.arguments + event.delta)
            else -> it
        }
    })
    // 回退了：这一步吐出来的半截全部作废。留着的话，等一次性结果一到，同一句话会像是被说了两遍。
    // 第一步回退就整个退回墨条；后面的步骤回退，前面已经落定的块留着
    LlmEvent.FellBack ->
        if (step == 0) ChatMessage.AssistantTurn(id, streaming = true, fellBack = true, startedAt = startedAt)
        else copy(blocks = blocks.filter { it.step < step }, idle = true)
    is LlmEvent.Done -> this
}

/** 参数收全、马上执行。一次性请求没有流式事件，块在这里才第一次出现 */
private fun ChatMessage.AssistantTurn.startingTool(call: Item.ToolCall, traced: Set<String>): ChatMessage.AssistantTurn {
    val exists = blocks.any { it.key == call.callId }
    return when {
        call.name == AskUserTool.NAME ->
            if (exists) withAsk(call.callId) { it.copy(arguments = call.arguments) }.copy(idle = false)
            else copy(blocks = blocks + TurnBlock.Ask(call.callId, step, call.arguments), idle = false)
        call.name in traced -> copy(
            blocks = if (exists) blocks.map {
                if (it is TurnBlock.Trace && it.callId == call.callId) it.copy(arguments = call.arguments) else it
            } else blocks + TurnBlock.Trace(call.callId, call.name, step, call.arguments),
            idle = false
        )
        else -> copy(idle = true)
    }.settleThought()
}

private fun ChatMessage.AssistantTurn.finishingTool(event: AgentEvent.ToolFinished, traced: Set<String>): ChatMessage.AssistantTurn {
    val call = event.call
    val outcome = event.outcome
    return when {
        call.name == AskUserTool.NAME -> {
            val answer = outcome.payload as? AskAnswer
            // 问卡不合规、根本没问出去：卡片拿掉，模型会照原因改好再问一次
            if (answer == null && !outcome.ok) copy(blocks = blocks.filterNot { it.key == call.callId }, idle = true)
            else if (answer != null && blocks.any { it is TurnBlock.Ask && it.callId == call.callId && it.state == AskState.READY }) answered(call.callId, answer)
            else copy(idle = true)
        }
        call.name in traced -> copy(
            blocks = blocks.map {
                if (it is TurnBlock.Trace && it.callId == call.callId) it.copy(
                    arguments = call.arguments,
                    state = if (outcome.ok) TraceState.OK else TraceState.FAILED,
                    output = outcome.output, ref = outcome.ref, doneAt = System.currentTimeMillis()
                ) else it
            },
            idle = true
        )
        else -> copy(idle = true)
    }
}

/** 思考之后的第一块内容到了（或者整个回合结束）：「想了 N 秒」就定格在这一刻 */
private fun ChatMessage.AssistantTurn.settleThought() =
    if (reasoning.isNotEmpty() && thoughtMillis == null) {
        copy(thoughtMillis = System.currentTimeMillis() - startedAt)
    } else this

/**
 * 从存下来的轮次重建画面（重启后打开对话页）。见 DESIGN.md §6.1
 *
 * 只还原落定的样子：对勾不再描、印不再盖，气泡不再升起，思考过程本来就不存。
 * 痕靠工具调用的参数 + 结果里的 ok / ref 还原，问卡靠结果末行的 `answer=…`；
 * 那条提醒后来被改过、删过，这里画的仍是当时的样子 —— 它是对话记录，不是提醒列表。
 * 只剩用户一句话的轮（当时失败了、没重试）只画气泡。
 */
fun restoredMessages(turns: List<Turn>, traced: Set<String>, newId: () -> Long): List<ChatMessage> = turns.flatMap { turn ->
    val user = ChatMessage.UserText(newId(), turn.input.text, sentAt = 0, trigger = turn.input.trigger)
    val results = turn.items.filterIsInstance<Item.ToolResult>().associateBy { it.callId }
    val blocks = mutableListOf<TurnBlock>()
    turn.items.forEach { item ->
        when (item) {
            is Item.AssistantMessage -> {
                val last = blocks.lastOrNull()
                if (last is TurnBlock.Prose) blocks[blocks.lastIndex] = last.copy(text = last.text + item.text)
                else blocks += TurnBlock.Prose("p${blocks.size}", 0, item.text)
            }
            is Item.ToolCall -> {
                val r = results[item.callId]
                when {
                    item.name == AskUserTool.NAME -> {
                        val request = AskUserTool.requestOf(item.arguments)
                        when (val answer = r?.output?.let(AskUserTool::answerOf)) {
                            // 没问出去（参数不合规）的那次不画
                            null -> if (r == null || request != null && AskUserTool.problemOf(request) == null) {
                                blocks += TurnBlock.Ask(item.callId, 0, item.arguments, request, AskState.UNANSWERED)
                            }
                            is AskAnswer.Picked -> blocks += TurnBlock.Ask(item.callId, 0, item.arguments, request, AskState.ANSWERED, answer.picks)
                            is AskAnswer.Said -> {
                                blocks += TurnBlock.Ask(item.callId, 0, item.arguments, request, AskState.SAID)
                                blocks += TurnBlock.Said("said-${item.callId}", 0, answer.text, 0)
                            }
                            AskAnswer.Unanswered -> blocks += TurnBlock.Ask(item.callId, 0, item.arguments, request, AskState.UNANSWERED)
                        }
                    }
                    item.name in traced -> blocks += TurnBlock.Trace(
                        item.callId, item.name, 0, item.arguments,
                        state = if (r?.ok == true) TraceState.OK else TraceState.FAILED,
                        output = r?.output, ref = r?.ref
                    )
                }
            }
            else -> Unit
        }
    }
    if (blocks.isEmpty()) return@flatMap listOf(user)
    listOf(user, ChatMessage.AssistantTurn(id = newId(), blocks = blocks, idle = false, startedAt = 0))
}
