package com.abc.daodian.assistant.memory.tidy

import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentInfo
import com.abc.daodian.runtime.memory.MemoryBackend

/**
 * 记忆整理员：后台看一段对话，抽记忆、攒够了顺手写摘要，一次调用交上来（DESIGN.md §6.9）。
 * 什么时候跑、看哪一段、结果怎么记归 [Tidy]；它说什么归 [TidyPrompt]。
 */
object TidyAgent : AgentInfo {

    override val id = "tidy"
    override val label = "记忆整理员"
    override val owner = "assistant"
    override val wakes = "后台：对话闲下来 10 分钟、攒够 16 轮"
    override val prompts = listOf(TidyPrompt.PROMPT)

    /** [compact] = 这次要写摘要；[remember] = 记忆改动收不收（「聊完自己整理」开没开） */
    fun build(book: MemoryBackend, compact: Boolean, remember: Boolean) =
        Agent(this, TidyPrompt.PROMPT, listOf(SaveTidyTool(book, compact, remember)), maxSteps = 3)
}
