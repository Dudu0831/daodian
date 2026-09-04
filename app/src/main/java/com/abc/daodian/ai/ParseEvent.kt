package com.abc.daodian.ai

import kotlinx.coroutines.flow.Flow
import java.time.ZonedDateTime

/**
 * 一次解析过程中，模型这一路都干了什么。见 DESIGN.md §6.7
 *
 * **终局仍然是 [ParseResult]** —— 流式只改变「过程能不能看见」，不改变
 * 「一句话变成一条记录」的判定。所以 MainViewModel 里落库排期那一段一个字都不用动，
 * 风险被关在网络层和界面层，碰不到 schedule/ 和 data/。
 */
sealed interface ParseEvent {

    /** 推理模型的思考过程。普通模型压根不发这类事件，界面必须能在「没有思考块」时长得正常 */
    data class Reasoning(val delta: String) : ParseEvent

    /** 反问 / 闲聊的正文，逐字来 */
    data class Text(val delta: String) : ParseEvent

    /** 模型开始调工具了，带工具名 */
    data class ToolStarted(val name: String) : ParseEvent

    /** 工具参数的 JSON 片段，逐字来 */
    data class ToolArgs(val delta: String) : ParseEvent

    /**
     * 流没走通，已经退回一次性请求。界面收到它应该把已经吐出来的半截字扔掉、
     * 退回骨架条 —— 半截字加上后面一次性蹦出来的完整答案，会看着像模型说了两遍。
     */
    data object FellBack : ParseEvent

    /** 终局。整条流有且只有一个 Done */
    data class Done(val result: ParseResult) : ParseEvent
}

/**
 * 能把过程吐出来的解析器。
 *
 * 继承 [ReminderParser] 不是为了好看：[parse] 是流式失败时的回退路径，
 * 两个方法必须由同一个实现类提供，否则回退时还得再拼一遍参数。
 */
interface StreamingReminderParser : ReminderParser {
    fun parseStream(
        input: String,
        now: ZonedDateTime,
        history: List<ChatTurn> = emptyList()
    ): Flow<ParseEvent>
}
