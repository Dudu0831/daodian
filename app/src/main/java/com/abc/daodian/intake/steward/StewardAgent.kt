package com.abc.daodian.intake.steward

import com.abc.daodian.intake.core.steward.DecisionBuffer
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentInfo

/**
 * 通知管家：收件箱里的待办 → 后台一轮模型 → 每条交给哪个模块、或者不相关（DESIGN.md §12.4）。
 * 界面上不露面（不挂顶栏）；手上只有 `hand_off`、`ignore`，自己不干活。
 * 什么时候跑、拿哪一批、结果怎么落归 [Steward]；它说什么归 [StewardPrompt]
 */
object StewardAgent : AgentInfo {

    override val id = "steward"
    override val label = "通知管家"
    override val owner = "intake"
    override val wakes = "后台：收件箱来了新的，防抖 3 秒；打开 app"
    override val prompts = listOf(StewardPrompt.PROMPT)

    /** 一批最多 [Steward.BATCH] 条，一步里能并着调好几个工具，多给几步收尾 */
    fun build(buffer: DecisionBuffer, destinations: List<String>) =
        Agent(this, StewardPrompt.PROMPT, listOf(HandOffTool(buffer, destinations), IgnoreTool(buffer)), maxSteps = 8)
}
