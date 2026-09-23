package com.abc.daodian.ui

import android.content.Context
import com.abc.daodian.harness.AgentLoop
import com.abc.daodian.harness.builtin.ledger.LedgerPrompt
import com.abc.daodian.harness.builtin.ledger.LedgerTools
import com.abc.daodian.harness.builtin.reminder.CreateReminderTool
import com.abc.daodian.harness.llm.ResponsesClient
import com.abc.daodian.harness.prompt.HarnessPrompt
import com.abc.daodian.harness.provider.ProviderProfile
import com.abc.daodian.harness.tool.ToolRegistry
import com.abc.daodian.ledger.LedgerStore

/**
 * 把 harness 接到这个 app 上：模型用哪家、工具落库落到哪。对话页和桌面速记共用。
 *
 * 工具有两组：建提醒，和记账（查、补记、改、加类别 —— 见 [LedgerTools.forChat]，写操作都排队拿账本锁，
 * 不会和后台整理同时写）。加新本事 = 往这里的列表里加工具、往提示词里加一段。
 *
 * 按配置缓存一份 —— [ResponsesClient] 自带一个 OkHttp 客户端，一句话新建一个等于
 * 白扔掉连接池。配置一改，两边下一句话同时换过去。
 */
object Agents {

    /** 对话 agent 的 system：提醒一段 + 记账一段。两段都是常量，拼起来逐字节稳定 */
    val SYSTEM = HarnessPrompt.SYSTEM + "\n\n" + LedgerPrompt.CHAT

    private var cached: Pair<ProviderProfile, AgentLoop>? = null

    @Synchronized
    fun of(context: Context, profile: ProviderProfile): AgentLoop {
        cached?.let { (p, loop) -> if (p == profile) return loop }
        val app = context.applicationContext
        val tools = ToolRegistry(
            listOf(
                CreateReminderTool { plan, ctx -> PlanCommitter.commit(app, ctx.userInput, plan, profile.model) }
            ) + LedgerTools.forChat(LedgerStore.get(app))
        )
        return AgentLoop(ResponsesClient(profile), tools, SYSTEM).also { cached = profile to it }
    }
}
