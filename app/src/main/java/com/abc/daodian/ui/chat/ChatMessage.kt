package com.abc.daodian.ui.chat

import com.abc.daodian.ai.ReminderPlan

/**
 * 对话流里的一条消息。见 DESIGN.md §02 / 设计稿 daodian-ui-mockups。
 *
 * 只保留纯文本往返和卡片两类；「思考中」是本地占位态，不进历史（见 ChatTurn 的说明）。
 */
sealed interface ChatMessage {
    val id: Long

    data class UserText(override val id: Long, val text: String) : ChatMessage

    data class Thinking(override val id: Long) : ChatMessage

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
