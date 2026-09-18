package com.abc.daodian.ui.chat

import com.abc.daodian.ai.ParseEvent
import com.abc.daodian.ai.ParseResult
import com.abc.daodian.ai.ReminderPlan

/**
 * 对话流里的一条消息。见 DESIGN.md §02 / §6.7，设计稿见 CLAUDE.md 里的画布链接。
 *
 * 只有两类：用户说的话，和助手的一个回合。
 * 助手回合是**原地长大**的 —— 从「一个字都没有」一路长到「正文 + 卡片」，
 * 中途不换消息类型、不清屏。上一版是流式占位消失、卡片另起一条，正文被丢掉了。
 */
sealed interface ChatMessage {
    val id: Long

    data class UserText(
        override val id: Long,
        val text: String,
        /** 气泡只在刚发出时从输入框升起一次，列表滚回来重组时不再升 */
        val sentAt: Long = System.currentTimeMillis()
    ) : ChatMessage

    /**
     * 助手的一个回合。四块内容按这个顺序摞：
     * 思考 → 卡片 → 正文（→ 出错时的出路）。
     *
     * 卡片和工具行是同一个元素，形态由 [cardPhaseOf] 从这里的字段推出来：
     * 在建提醒 → 起稿 → 落印 → 收起；闸门拦下时退回成「没记下」的工具行。见决策 6.2。
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
        /** 建成的提醒。null = 这一回合没建出东西 */
        val reminderId: Long? = null,
        val plan: ReminderPlan? = null,
        val cardCollapsed: Boolean = false,
        /** 落印的时刻。印只在刚落下时盖一次 —— 列表滚回来重组时不能再盖一遍 */
        val stampedAt: Long = 0,
        /** 工具跑了、但校验闸门没放行（时间在过去、要确认）：为什么没记 */
        val gateNote: String? = null,
        /** 解析失败，挂「手动填一条 / 重试」 */
        val isError: Boolean = false,
        /** 还在流 —— 决定要不要画光标 */
        val streaming: Boolean = false,
        /** 流式没走通，正在走一次性请求 —— 墨条上方挂一行小字 */
        val fellBack: Boolean = false,
        val startedAt: Long = System.currentTimeMillis()
    ) : ChatMessage {

        /** 请求发出去了但一个字都还没回来 —— 界面退回墨条，空着的框比墨条更让人发懵 */
        val isBlank: Boolean
            get() = reasoning.isEmpty() && text.isEmpty() && toolName == null && plan == null

        /** 思考块折成一行「想了 N 秒」：思考流完（后面的内容来了），或者整个回合结束 */
        val reasoningFolded: Boolean
            get() = !streaming || thoughtMillis != null
    }
}

/*
 * 一个回合怎么随着流长大。对话页（MainViewModel）和桌面速记（ui/quick）共用 ——
 * 同一句话在两处长得一模一样，靠的就是这几条规则只有一份。
 */

/** 把一个过程事件叠到这个回合上 */
fun ChatMessage.AssistantTurn.patched(event: ParseEvent): ChatMessage.AssistantTurn = when (event) {
    is ParseEvent.Reasoning -> copy(reasoning = reasoning + event.delta)
    is ParseEvent.Text -> copy(text = text + event.delta).settleThought()
    is ParseEvent.ToolStarted -> copy(toolName = event.name, toolRunning = true, toolArgs = "").settleThought()
    // 参数本身不上屏，只从里面抠草稿卡的标题和时间（见 DraftArgs）
    is ParseEvent.ToolArgs -> copy(toolArgs = toolArgs + event.delta)
    // 回退了：吐出来的半截全部作废，退回墨条。
    // 留着的话，等下一次性结果一到，同一句话会像是被说了两遍
    ParseEvent.FellBack -> ChatMessage.AssistantTurn(id, streaming = true, fellBack = true)
    is ParseEvent.Done -> this
}

/** 流走完了：卡片长在同一个回合里，正文按各自的规则留下。[reminderId] 是落库后的 id，没建成就是 null */
fun ChatMessage.AssistantTurn.finished(result: ParseResult?, reminderId: Long?): ChatMessage.AssistantTurn =
    when (result) {
        is ParseResult.Ok -> copy(
            streaming = false, toolRunning = false,
            reminderId = reminderId, plan = result.plan,
            stampedAt = System.currentTimeMillis()
        )
        // 工具跑了、闸门没放行：卡片退回「没记下」，下面说清原因
        is ParseResult.NeedsClarification -> if (toolName != null) {
            copy(streaming = false, toolRunning = false, gateNote = result.question)
        } else {
            copy(
                streaming = false, toolRunning = false,
                // 流里已经逐字吐过这句话了，别再覆盖一遍
                text = text.ifBlank { result.question }
            )
        }
        // 第二句「你可以自己填一条」由 UI 补，见 AssistantTurnRow
        is ParseResult.Failed, null -> copy(
            streaming = false, toolRunning = false, isError = true,
            text = "连不上服务器，这句话没能解析。"
        )
    }.settleThought()

/** 思考之后的第一块内容到了（或者整个回合结束）：「想了 N 秒」就定格在这一刻 */
private fun ChatMessage.AssistantTurn.settleThought() =
    if (reasoning.isNotEmpty() && thoughtMillis == null) {
        copy(thoughtMillis = System.currentTimeMillis() - startedAt)
    } else this

/**
 * 这个回合喂给下一轮的文本，没东西可喂就是 null。见 ChatTurn 的说明。
 *
 * 已建的提醒必须以「回执」的形式留在历史里：否则模型看到的是
 * 「用户：三分钟后提醒我喝水 / 用户：谢谢」—— 上一句请求像是没人应，
 * 它会好心再建一遍。真机上就是这么冒出重复提醒的。
 */
fun ChatMessage.AssistantTurn.historyText(): String? {
    // 思考过程不进历史：把模型自己的思考喂回给它没有意义，只会挤掉真正的上下文
    val said = buildString {
        append(text.trim())
        // 闸门问的那句也要进历史，否则用户下一句回答在模型眼里没有上文
        gateNote?.let {
            if (isNotEmpty()) append(" ")
            append(it)
        }
        plan?.let { plan ->
            if (isNotEmpty()) append(" ")
            append("（已建好${if (plan.allDay) "当天事项" else "提醒"}：「${plan.title}」，${plan.firstTriggerAt}")
            plan.rrule?.let { append("，重复 $it") }
            append("。这条已经落库排期了，不要重复建）")
        }
    }
    return said.takeIf { it.isNotBlank() }
}
