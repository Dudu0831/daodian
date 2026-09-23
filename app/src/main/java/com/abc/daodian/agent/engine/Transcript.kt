package com.abc.daodian.agent.engine

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 对话记录的最小单位。一轮 = 用户一句话 + agent 为它做的所有事（说话、调工具、拿结果）。
 *
 * 历史**保留完整结构**：之前轮次的工具调用和结果原样重放给模型，不压成人话回执。
 * 唯一不重放的是思考过程 —— 推理条目离开当次请求就没意义，第三方网关还可能因为
 * 缺 encrypted_content 直接拒收。
 */
sealed interface Item {

    /**
     * 一轮的开头。通常是用户说的话；[trigger] 为 true 时是 app 自己发起的（比如每晚对账），
     * 用户没说过这句，界面上画成一条分隔线而不是气泡。
     * [at] 是这句话的时刻，重放时跟着一起给模型，「明天」才算得对
     */
    data class UserMessage(val text: String, val at: ZonedDateTime, val trigger: Boolean = false) : Item {
        /** 喂给模型的样子：`[2026-09-18 21:03 周五 +08:00] 原话`。格式在 HarnessPrompt 里有交代 */
        fun content(): String =
            "[${at.format(STAMP)} ${WEEKDAY[at.dayOfWeek.value - 1]} ${at.offset}] " +
                (if (trigger) "$TRIGGER_MARK$text" else text)
    }

    /** agent 说给用户听的话 */
    data class AssistantMessage(val text: String) : Item

    /** 模型要求调一个工具。[callId] 由服务端给，结果必须拿同一个 id 回传 */
    data class ToolCall(val callId: String, val name: String, val arguments: String) : Item

    /**
     * 工具执行完回给模型的结果。每个 [ToolCall] 有且只有一个。
     *
     * @param ok 办成没有。模型看不到，重建画面时靠它决定卡片是「已记下」还是「没记下」
     * @param ref 办成后指向的那条记录（比如新提醒的 id）。同样只给重建画面用
     */
    data class ToolResult(val callId: String, val output: String, val ok: Boolean, val ref: Long? = null) : Item

    companion object {
        /** app 自己发起的一轮，开头打这个标记。提示词里有交代：这不是用户说的，别当成他的原话 */
        const val TRIGGER_MARK = "（app 自动发起，不是用户说的）"

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val WEEKDAY = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

/** 一轮。第一项永远是 [Item.UserMessage]。[id] 在一个 [Session] 里递增，落盘时按它认轮 */
data class Turn(val id: Long, val items: List<Item>) {
    init {
        require(items.firstOrNull() is Item.UserMessage) { "一轮必须从用户的话开始" }
    }

    val input: Item.UserMessage get() = items.first() as Item.UserMessage
}
