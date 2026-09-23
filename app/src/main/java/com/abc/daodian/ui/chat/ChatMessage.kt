package com.abc.daodian.ui.chat

import com.abc.daodian.harness.AgentEvent
import com.abc.daodian.harness.Item
import com.abc.daodian.harness.StopReason
import com.abc.daodian.harness.Turn
import com.abc.daodian.harness.builtin.ledger.LedgerTools
import com.abc.daodian.harness.builtin.reminder.CreateReminderTool
import com.abc.daodian.harness.builtin.reminder.ReminderPlan
import com.abc.daodian.harness.llm.LlmEvent

/**
 * 对话流里的一条消息。见 DESIGN.md §02 / §6.7，设计稿见 CLAUDE.md 里的画布链接。
 *
 * 只有两类：用户说的话，和助手的一个回合。
 * 助手回合是**原地长大**的 —— 从「一个字都没有」一路长到「正文 + 卡片」，
 * 中途不换消息类型、不清屏。一个回合里 agent 可能调好几次模型（调工具 → 看结果 → 再说话），
 * 在界面上仍然是同一个回合接着长。
 *
 * 这里只是画面的状态；喂给模型的历史在 [com.abc.daodian.harness.Session] 里，两者各管各的。
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

    /**
     * 助手的一个回合。四块内容按这个顺序摞：
     * 思考 → 卡片 → 正文（→ 出错时的出路）。
     *
     * 卡片和工具行是同一个元素，形态由 [cardPhaseOf] 从这里的字段推出来：
     * 在建提醒 → 起稿 →（等你点头）→ 落印 → 收起；没建成时退回成「没记下」的工具行。见决策 6.2。
     *
     * 一个回合只有一张卡：同一回合里模型要是建了第二条，卡片停在第一条，第二条由它自己在正文里交代。
     */
    data class AssistantTurn(
        override val id: Long,
        /** 推理模型的思考过程。普通模型压根不发，这里就一直是空的 */
        val reasoning: String = "",
        val reasoningOpen: Boolean = false,
        /** 思考了多久。第一段正文或工具调用一到就定格；null = 还在想，或者压根没思考 */
        val thoughtMillis: Long? = null,
        val text: String = "",
        val toolName: String? = null,
        /** 工具正在跑 */
        val toolRunning: Boolean = false,
        /** 工具参数的原始 JSON 片段。不上屏，只拿来抠草稿卡的标题和时间，见 [DraftArgs] */
        val toolArgs: String = "",
        /** 默认授权模式下，卡片停在草稿上等你点「记下」/「不要」 */
        val awaitingApproval: Boolean = false,
        /** 工具跑完了但没建成：闸门拦下，或者你点了「不要」。为什么，由模型在正文里说 */
        val toolRejected: Boolean = false,
        /** 建成的提醒。null = 这一回合没建出东西 */
        val reminderId: Long? = null,
        val plan: ReminderPlan? = null,
        val cardCollapsed: Boolean = false,
        /** 落印的时刻。印只在刚落下时盖一次 —— 列表滚回来重组时不能再盖一遍 */
        val stampedAt: Long = 0,
        /** 解析失败，挂「手动填一条 / 重试」 */
        val isError: Boolean = false,
        /** 还在流 —— 决定要不要画光标 */
        val streaming: Boolean = false,
        /** 流式没走通，正在走一次性请求 —— 墨条上方挂一行小字 */
        val fellBack: Boolean = false,
        /** 当前是这一轮的第几次模型调用，和这一步的正文从 [text] 的哪里开始。回退时只擦这一步的字 */
        val step: Int = 0,
        val stepTextFrom: Int = 0,
        /** 这一回合里的记账操作（查账、记一笔、改账）。和提醒卡片分开画，一回合可以有好几个 */
        val ledgerOps: List<LedgerOp> = emptyList(),
        val startedAt: Long = System.currentTimeMillis()
    ) : ChatMessage {

        /** 请求发出去了但一个字都还没回来 —— 界面退回墨条，空着的框比墨条更让人发懵 */
        val isBlank: Boolean
            get() = reasoning.isEmpty() && text.isEmpty() && toolName == null && plan == null && ledgerOps.isEmpty()

        /** 思考块折成一行「想了 N 秒」：思考流完（后面的内容来了），或者整个回合结束 */
        val reasoningFolded: Boolean
            get() = !streaming || thoughtMillis != null

        /** 卡片已经有了结局（建成 / 没建成），后面再来的工具调用不再改它 */
        val cardSettled: Boolean
            get() = plan != null || toolRejected
    }
}

/**
 * 回合里的一次记账操作，画成卡片下面的一行回执。状态只往前走：在跑 →（等你点头）→ 办成 / 没办成。
 * [arguments] 是工具参数的原始 JSON，回执上的人话从它翻（见 ApprovalDock 的 approvalSummaryOf）。
 */
data class LedgerOp(
    val callId: String,
    val tool: String,
    val arguments: String = "",
    val awaiting: Boolean = false,
    /** null = 还在跑；true 办成；false 没办成（闸门拦下 / 你说了不） */
    val ok: Boolean? = null,
    /** 工具回给模型的第一行（「改好了 1 笔」「共 3 笔：支出 …」），回执上当细节 */
    val result: String? = null
)

private fun isLedger(tool: String) = tool in LedgerTools.NAMES

/*
 * 一个回合怎么随着 agent 的事件长大。对话页（MainViewModel）和桌面速记（ui/quick）共用 ——
 * 同一句话在两处长得一模一样，靠的就是这几条规则只有一份。
 */

/** 把一个事件叠到这个回合上 */
fun ChatMessage.AssistantTurn.patched(event: AgentEvent): ChatMessage.AssistantTurn = when (event) {
    is AgentEvent.Model -> enteringStep(event.step).patchedModel(event.event)
    // 记账的调用走自己的回执，不碰提醒卡片
    is AgentEvent.AwaitingApproval -> if (isLedger(event.call.name)) {
        withOp(event.call.callId, event.call.name) { it.copy(arguments = event.call.arguments, awaiting = true) }
    } else copy(awaitingApproval = true)
    is AgentEvent.ToolDenied -> if (isLedger(event.call.name)) {
        withOp(event.call.callId, event.call.name) { it.copy(arguments = event.call.arguments, awaiting = false, ok = false) }
    } else if (cardSettled) this else copy(awaitingApproval = false, toolRunning = false, toolRejected = true)
    is AgentEvent.ToolFinished -> if (isLedger(event.call.name)) {
        withOp(event.call.callId, event.call.name) {
            it.copy(
                arguments = event.call.arguments, awaiting = false, ok = event.outcome.ok,
                result = event.outcome.output.lineSequence().firstOrNull()?.trim()
            )
        }
    } else if (cardSettled) this else {
        val created = event.outcome.payload as? CreateReminderTool.Created
        if (created != null) {
            copy(
                awaitingApproval = false, toolRunning = false,
                reminderId = created.reminderId, plan = created.plan,
                stampedAt = System.currentTimeMillis()
            )
        } else {
            copy(awaitingApproval = false, toolRunning = false, toolRejected = true)
        }
    }
    is AgentEvent.Finished -> copy(
        streaming = false, toolRunning = false,
        // 撞到步数上限多半是模型在原地打转，一句话都没留下时替它交代一声
        text = if (event.stop == StopReason.STEP_LIMIT && text.isBlank()) "绕了几圈也没办成，换个说法试试？" else text
    ).settleThought()
    // 提醒已经建了、收尾那步才断：不挂「重试」—— 重试等于把这句话再办一遍，会建出第二条
    is AgentEvent.Failed -> if (plan != null) copy(
        streaming = false, toolRunning = false,
        text = text.take(stepTextFrom).ifBlank { "提醒建好了，后面的话没说完就断了。" }
    ) else copy(
        // 第二句「你可以自己填一条」由 UI 补，见 AssistantTurnRow
        streaming = false, toolRunning = false, awaitingApproval = false, isError = true,
        text = "连不上服务器，这句话没能解析。"
    ).settleThought()
}

/** 改（没有就先加上）这一回合里的某次记账操作 */
private fun ChatMessage.AssistantTurn.withOp(callId: String, tool: String, change: (LedgerOp) -> LedgerOp): ChatMessage.AssistantTurn {
    val existing = ledgerOps.firstOrNull { it.callId == callId }
    return if (existing == null) copy(ledgerOps = ledgerOps + change(LedgerOp(callId, tool)))
    else copy(ledgerOps = ledgerOps.map { if (it.callId == callId) change(it) else it })
}

/** 进入新的一步：记下这一步的正文从哪开始 */
private fun ChatMessage.AssistantTurn.enteringStep(step: Int) =
    if (step == this.step) this else copy(step = step, stepTextFrom = text.length)

private fun ChatMessage.AssistantTurn.patchedModel(event: LlmEvent): ChatMessage.AssistantTurn = when (event) {
    is LlmEvent.Reasoning -> copy(reasoning = reasoning + event.delta)
    is LlmEvent.Text -> copy(text = text + event.delta).settleThought()
    is LlmEvent.ToolStarted -> when {
        isLedger(event.name) -> withOp(event.callId, event.name) { it }.settleThought()
        cardSettled -> settleThought()
        else -> copy(toolName = event.name, toolRunning = true, toolArgs = "").settleThought()
    }
    // 参数本身不上屏，只从里面抠草稿卡的标题和时间（见 DraftArgs）
    is LlmEvent.ToolArgs -> when {
        ledgerOps.any { it.callId == event.callId } ->
            withOp(event.callId, "") { it.copy(arguments = it.arguments + event.delta) }
        cardSettled -> this
        else -> copy(toolArgs = toolArgs + event.delta)
    }
    // 回退了：这一步吐出来的半截全部作废。留着的话，等下一次性结果一到，同一句话会像是被说了两遍。
    // 第一步回退就整个退回墨条；后面的步骤回退，前面已经落定的卡片和正文留着
    LlmEvent.FellBack ->
        if (step == 0) ChatMessage.AssistantTurn(id, streaming = true, fellBack = true)
        else copy(
            text = text.take(stepTextFrom),
            toolName = if (cardSettled) toolName else null,
            toolArgs = if (cardSettled) toolArgs else "",
            toolRunning = false,
            ledgerOps = ledgerOps.filter { it.ok != null || it.awaiting }
        )
    is LlmEvent.Done -> this
}

/** 思考之后的第一块内容到了（或者整个回合结束）：「想了 N 秒」就定格在这一刻 */
private fun ChatMessage.AssistantTurn.settleThought() =
    if (reasoning.isNotEmpty() && thoughtMillis == null) {
        copy(thoughtMillis = System.currentTimeMillis() - startedAt)
    } else this

/**
 * 从存下来的轮次重建画面（重启后打开对话页）。见 DESIGN.md §6.8
 *
 * 只还原落定的样子：卡片一律收起、不再盖印，气泡不再升起，思考过程本来就不存。
 * 卡片靠 `create_reminder` 的参数 + 结果里的 ok / ref 还原；那条提醒后来被改过、删过，
 * 这里画的仍是当时建的样子 —— 它是对话记录，不是提醒列表。
 * 只剩用户一句话的轮（当时失败了、没重试）只画气泡。
 */
fun restoredMessages(turns: List<Turn>, newId: () -> Long): List<ChatMessage> = turns.flatMap { turn ->
    val user = ChatMessage.UserText(newId(), turn.input.text, sentAt = 0, trigger = turn.input.trigger)
    val text = turn.items.filterIsInstance<Item.AssistantMessage>().joinToString("") { it.text }
    val results = turn.items.filterIsInstance<Item.ToolResult>().associateBy { it.callId }
    val ops = turn.items.filterIsInstance<Item.ToolCall>().filter { isLedger(it.name) }.map { c ->
        val r = results[c.callId]
        LedgerOp(c.callId, c.name, c.arguments, ok = r?.ok ?: false, result = r?.output?.lineSequence()?.firstOrNull()?.trim())
    }
    val call = turn.items.filterIsInstance<Item.ToolCall>().firstOrNull { !isLedger(it.name) }
    if (text.isEmpty() && call == null && ops.isEmpty()) return@flatMap listOf(user)

    val result = call?.let { c -> results[c.callId] }
    val plan = call?.takeIf { result?.ok == true && result.ref != null }
        ?.let { CreateReminderTool.planOf(it.arguments) }
    listOf(
        user,
        ChatMessage.AssistantTurn(
            id = newId(),
            text = text,
            toolName = call?.name,
            toolArgs = call?.arguments.orEmpty(),
            toolRejected = call != null && plan == null,
            reminderId = if (plan != null) result?.ref else null,
            plan = plan,
            cardCollapsed = true,
            ledgerOps = ops,
            startedAt = 0
        )
    )
}
