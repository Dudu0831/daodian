package com.abc.daodian.agent.conversation

import android.content.Context
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.context.FoldDrawings
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.model.ResponsesClient
import com.abc.daodian.agent.model.provider.ProviderProfile
import com.abc.daodian.agent.prompt.BasePrompt

/**
 * 对话 agent：模型用哪家、带哪些工具、system 怎么拼。对话页和桌面速记共用。
 *
 * 工具 = 问人（[AskUserTool]，拿不准时出问卡）+ 各模块给的（[com.abc.daodian.agent.feature.Feature.tools]）；
 * system = 基础提示词 + 各模块的那一段，按 `Features.kt` 的顺序拼。这里不认识任何具体模块。
 *
 * 按配置缓存一份 —— [ResponsesClient] 自带一个 OkHttp 客户端，一句话新建一个等于
 * 白扔掉连接池。配置一改，两边下一句话同时换过去。
 */
object ChatAgent {

    /** 各段都是常量，拼起来逐字节稳定（前缀缓存） */
    val system: String
        get() = (listOf(BasePrompt.SYSTEM) + FeatureRegistry.features.map { it.prompt }).joinToString("\n\n")

    fun tools(context: Context): List<Tool> =
        listOf(AskUserTool()) + FeatureRegistry.features.flatMap { it.tools(context.applicationContext) }

    /** 会在对话里留痕的工具：写操作都留，只读的（查账）不留，问人的画问卡 */
    fun traced(context: Context): Set<String> =
        tools(context).filter { it.effect == ToolEffect.WRITE }.map { it.name }.toSet()

    private var cached: Pair<ProviderProfile, AgentLoop>? = null

    @Synchronized
    fun of(context: Context, profile: ProviderProfile): AgentLoop {
        cached?.let { (p, loop) -> if (p == profile) return loop }
        // 旧轮次里画过的图折成一句再喂回去（DESIGN.md §6.11）
        return AgentLoop(ResponsesClient(profile), ToolRegistry(tools(context)), system, FoldDrawings())
            .also { cached = profile to it }
    }
}
