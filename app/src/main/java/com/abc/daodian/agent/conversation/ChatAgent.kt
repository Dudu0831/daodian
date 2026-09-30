package com.abc.daodian.agent.conversation

import android.content.Context
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Turn
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.context.Compaction
import com.abc.daodian.agent.engine.context.ContextPolicy
import com.abc.daodian.agent.engine.context.FoldDrawings
import com.abc.daodian.agent.engine.context.FoldStale
import com.abc.daodian.agent.engine.context.LastTurns
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.memory.EditMemoryTool
import com.abc.daodian.agent.memory.MemoryBook
import com.abc.daodian.agent.model.ResponsesClient
import com.abc.daodian.agent.model.provider.ProviderProfile
import com.abc.daodian.agent.prompt.BasePrompt

/**
 * 对话 agent：模型用哪家、带哪些工具、system 怎么拼。对话页和桌面速记共用。
 *
 * 工具 = 问人（[AskUserTool]，拿不准时出问卡）+ 记忆（[EditMemoryTool]）+ 各模块给的（[com.abc.daodian.agent.feature.Feature.tools]）；
 * system = 基础提示词 + 各模块的那一段，按 `Features.kt` 的顺序拼。这里不认识任何具体模块。
 * 历史怎么喂见 [policy]；前面垫的记忆和摘要由调用方给（`memory/Recall`），DESIGN.md §6.9。
 *
 * 按配置缓存一份 —— [ResponsesClient] 自带一个 OkHttp 客户端，一句话新建一个等于
 * 白扔掉连接池。配置一改，两边下一句话同时换过去。
 */
object ChatAgent {

    /** 各段都是常量，拼起来逐字节稳定（前缀缓存）。没有提示词的模块（通知监听层）不占位置 */
    val system: String
        get() = (listOf(BasePrompt.SYSTEM) + FeatureRegistry.features.map { it.prompt }.filter { it.isNotBlank() }).joinToString("\n\n")

    fun tools(context: Context): List<Tool> =
        listOf(AskUserTool(), EditMemoryTool(MemoryBook.get(context))) +
            FeatureRegistry.features.flatMap { it.tools(context.applicationContext) }

    /** 结果过几轮就折掉的查询工具（[Tool.outputGoesStale]） */
    fun staleTools(context: Context): Set<String> =
        tools(context).filter { it.outputGoesStale }.map { it.name }.toSet()

    /**
     * 每步喂哪些轮：摘要之后的（AgentLoop 按 Recall 给的切）最多 [Compaction.HARD_CAP] 轮 ——
     * 正常早就压成摘要了，这是压缩一直不成时的兜底；旧查询结果、旧图折成一句（§6.8、§6.9）
     */
    private fun policy(stale: Set<String>, inner: ContextPolicy): ContextPolicy = FoldDrawings(FoldStale(stale, inner))

    /** 这几轮喂出去是什么样（不裁轮数）：后台整理估要不要压时用，和真发出去的一致 */
    fun foldView(context: Context, turns: List<Turn>): List<Turn> =
        policy(staleTools(context)) { it }.select(turns)

    /** 会在对话里留痕的工具：写操作都留，只读的（查账）不留，问人的画问卡 */
    fun traced(context: Context): Set<String> =
        tools(context).filter { it.effect == ToolEffect.WRITE }.map { it.name }.toSet()

    private var cached: Pair<ProviderProfile, AgentLoop>? = null

    @Synchronized
    fun of(context: Context, profile: ProviderProfile): AgentLoop {
        cached?.let { (p, loop) -> if (p == profile) return loop }
        val tools = tools(context)
        val stale = tools.filter { it.outputGoesStale }.map { it.name }.toSet()
        val app = context.applicationContext
        return AgentLoop(
            ResponsesClient(profile), ToolRegistry(tools), system, policy(stale, LastTurns(Compaction.HARD_CAP)),
            onRequest = { preamble, sent, request -> ContextTrace.log(app, preamble, sent, request) }
        ).also { cached = profile to it }
    }
}
