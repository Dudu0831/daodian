package com.abc.daodian.assistant

import android.content.Context
import com.abc.daodian.assistant.memory.EditMemoryTool
import com.abc.daodian.assistant.memory.MemoryBook
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentInfo
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.runtime.context.Compaction
import com.abc.daodian.runtime.context.ContextPolicy
import com.abc.daodian.runtime.context.FoldDrawings
import com.abc.daodian.runtime.context.FoldImages
import com.abc.daodian.runtime.context.FoldStale
import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.memory.MemoryBackend
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolEffect

/**
 * 助手：你在对话页说一句、在桌面速记说一句，都是它；每晚对账那一轮也是它，开场白由记账写。
 *
 * 工具 = 问人（[AskUserTool]，拿不准时出问卡）+ 记忆（[EditMemoryTool]）+ 各模块给的（[com.abc.daodian.assistant.plugin.AssistantPlugin.tools]）；
 * system = [AssistantPrompt]，全文写在那一个文件里，模块不往里追加。这里不认识任何具体模块。
 * 历史怎么喂见 [policy]；前面垫的记忆和摘要由调用方给（`memory/Recall`），DESIGN.md §6.9。
 * 怎么跑归 runtime（`AgentRuntime.run`）：客户端按配置缓存在那边，这里每句话现建一个 [Agent] 不费什么。
 */
object AssistantAgent : AgentInfo {

    override val id = "assistant"
    override val label = "助手"
    override val owner = "assistant"
    override val wakes = "你在对话页说一句；桌面速记；每晚对账由记账开场"
    override val prompts = listOf(AssistantPrompt.PROMPT)
    override val canAsk = true

    fun build(context: Context): Agent = build(tools(context))

    /** 拿一套现成的工具建（测试里没有 Context，工具用 [tools] 的另一个重载现拼） */
    fun build(tools: List<Tool>): Agent =
        Agent(this, AssistantPrompt.PROMPT, tools, policy(staleOf(tools), LastTurns(Compaction.HARD_CAP)))

    fun tools(context: Context): List<Tool> = tools(MemoryBook.get(context), AssistantPlugins.tools(context))

    /**
     * 助手自己的两样（问人、记忆）在前，各模块给的接在后面（按 `Modules.kt` 的顺序）。
     * 不要 Context：`AgentsTest`、`LiveGatewayTest` 用它拼出和真机上一样的一套
     */
    fun tools(memory: MemoryBackend, modules: List<Tool>): List<Tool> =
        listOf(AskUserTool(), EditMemoryTool(memory)) + modules

    /** 结果过几轮就折掉的查询工具（[Tool.outputGoesStale]） */
    fun staleTools(context: Context): Set<String> = staleOf(tools(context))

    private fun staleOf(tools: List<Tool>): Set<String> = tools.filter { it.outputGoesStale }.map { it.name }.toSet()

    /**
     * 每步喂哪些轮：摘要之后的（AgentLoop 按 Recall 给的切）最多 [Compaction.HARD_CAP] 轮 ——
     * 正常早就压成摘要了，这是压缩一直不成时的兜底；旧查询结果、旧图、他早先发来的图片折成一句（§6.8、§6.9、§6.10）
     */
    private fun policy(stale: Set<String>, inner: ContextPolicy): ContextPolicy =
        FoldImages(FoldDrawings(FoldStale(stale, inner)))

    /** 这几轮喂出去是什么样（不裁轮数）：后台整理估要不要压时用，和真发出去的一致 */
    fun foldView(context: Context, turns: List<Turn>): List<Turn> =
        policy(staleTools(context)) { it }.select(turns)

    /** 会在对话里留痕的工具：写操作都留，只读的（查账）不留，问人的画问卡 */
    fun traced(context: Context): Set<String> =
        tools(context).filter { it.effect == ToolEffect.WRITE }.map { it.name }.toSet()
}
