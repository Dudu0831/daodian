package com.abc.daodian.ui.chat

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

    data class UserText(override val id: Long, val text: String) : ChatMessage

    /**
     * 助手的一个回合。四块内容按这个顺序摞：
     * 思考 → 工具行 → 正文 → 卡片。
     *
     * 工具行的显隐规则是 [showToolRow]：**有卡片就藏，没卡片就留**。
     * 落定之后卡片上的「已记下」和工具名说的是同一件事，留着是同义反复；
     * 但工具跑了却没长出卡片（校验闸门拦下了算错的时间，见 §6.5），
     * 它就是「模型动过手」的唯一痕迹，必须留。
     */
    data class AssistantTurn(
        override val id: Long,
        /** 推理模型的思考过程。普通模型压根不发，这里就一直是空的 */
        val reasoning: String = "",
        val reasoningOpen: Boolean = false,
        val text: String = "",
        val toolName: String? = null,
        /** 工具正在跑 —— 工具行闪烁的那一档 */
        val toolRunning: Boolean = false,
        /** 建成的提醒。null = 这一回合没建出东西 */
        val reminderId: Long? = null,
        val plan: ReminderPlan? = null,
        val cardCollapsed: Boolean = false,
        /** 解析失败，挂「手动填一条 / 重试」 */
        val isError: Boolean = false,
        /** 还在流 —— 决定要不要画光标 */
        val streaming: Boolean = false,
        val startedAt: Long = System.currentTimeMillis()
    ) : ChatMessage {

        /** 请求发出去了但一个字都还没回来 —— 界面退回骨架条，空着的框比骨架条更让人发懵 */
        val isBlank: Boolean
            get() = reasoning.isEmpty() && text.isEmpty() && toolName == null && plan == null

        val showToolRow: Boolean
            get() = toolName != null && (toolRunning || plan == null)

        /** 思考流完之后折成一行「想了 N 秒」 */
        val thoughtSeconds: Int
            get() = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
    }
}
