package com.abc.daodian.ui

import android.content.Context
import com.abc.daodian.harness.AgentLoop
import com.abc.daodian.harness.builtin.reminder.CreateReminderTool
import com.abc.daodian.harness.llm.ResponsesClient
import com.abc.daodian.harness.prompt.HarnessPrompt
import com.abc.daodian.harness.provider.ProviderProfile
import com.abc.daodian.harness.tool.ToolRegistry

/**
 * 把 harness 接到这个 app 上：模型用哪家、工具落库落到哪。对话页和桌面速记共用。
 *
 * 按配置缓存一份 —— [ResponsesClient] 自带一个 OkHttp 客户端，一句话新建一个等于
 * 白扔掉连接池。配置一改，两边下一句话同时换过去。
 */
object Agents {

    private var cached: Pair<ProviderProfile, AgentLoop>? = null

    @Synchronized
    fun of(context: Context, profile: ProviderProfile): AgentLoop {
        cached?.let { (p, loop) -> if (p == profile) return loop }
        val app = context.applicationContext
        val tools = ToolRegistry(
            listOf(
                CreateReminderTool { plan, ctx -> PlanCommitter.commit(app, ctx.userInput, plan, profile.model) }
            )
        )
        return AgentLoop(ResponsesClient(profile), tools, HarnessPrompt.SYSTEM).also { cached = profile to it }
    }
}
