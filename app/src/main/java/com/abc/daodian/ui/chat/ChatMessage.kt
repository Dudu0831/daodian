package com.abc.daodian.ui.chat

import com.abc.daodian.ai.ReminderPlan

/**
 * 对话流里的一条消息。见 DESIGN.md §02 / 设计稿 daodian-ui-mockups。
 *
 * 只有纯文本往返和卡片两类会进历史；[Streaming] 和 [ReasoningTrace] 都是过程的痕迹，
 * 一律不进（见 ChatTurn 的说明）—— 模型的思考过程喂回给它自己没有意义，只会挤掉真正的上下文。
 */
sealed interface ChatMessage {
    val id: Long

    data class UserText(override val id: Long, val text: String) : ChatMessage

    /**
     * 正在流式解析。字是一路长出来的，见 DESIGN.md §6.7。
     *
     * 四个格子都空着 = 请求发出去了还没有任何回音，界面退回骨架条；
     * [fellBack] = 流式没跑通已经回退成一次性请求，半截字要擦掉。
     */
    data class Streaming(
        override val id: Long,
        val reasoning: String = "",
        val text: String = "",
        val toolName: String? = null,
        val toolArgs: String = "",
        val fellBack: Boolean = false,
        val startedAt: Long = System.currentTimeMillis()
    ) : ChatMessage {
        val isBlank: Boolean get() = reasoning.isEmpty() && text.isEmpty() && toolName == null
    }

    /**
     * 流完之后留下的一行「想了 3 秒 ›」。默认收着 ——
     * 思考是过程不是结论，不该长期占版面，但也不该看完就没了。
     */
    data class ReasoningTrace(
        override val id: Long,
        val text: String,
        val seconds: Int,
        val expanded: Boolean = false
    ) : ChatMessage

    /** 反问，或者失败。[isError] 决定圆点是红是绿、要不要挂「手动添加」按钮 */
    data class AssistantText(override val id: Long, val text: String, val isError: Boolean = false) : ChatMessage

    /**
     * 已经建好的提醒。工具调用一旦成功就直接落库了 —— 这张卡片不是「请确认」，
     * 是「已经这样了」的回执。「就这样」= 收起确认；「改一下」= 跳编辑页微调。
     */
    data class AssistantCard(
        override val id: Long,
        val reminderId: Long,
        val plan: ReminderPlan,
        val collapsed: Boolean = false
    ) : ChatMessage
}
