package com.abc.daodian.reminder.relay

import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentInfo
import com.abc.daodian.runtime.tool.ToolContext

/**
 * 派活：名单上的人发来的一句 → 后台一轮模型 → 要你做的事就建成提醒。只带 `create_reminder` 一个工具，问不了你：
 * 拿不准钟点就建成当天事项。收到一句、交给它、结果怎么记归 [Relay]；它说什么归 [RelayPrompt]。
 */
object RelayAgent : AgentInfo {

    override val id = "relay"
    override val label = "派活"
    override val owner = "reminder"
    override val wakes = "后台：名单上的人发来一句"
    override val prompts = listOf(RelayPrompt.PROMPT)

    /** [commit]：落库那一下由 [Relay] 给（写上是谁派的、一律响铃） */
    fun build(commit: suspend (ReminderPlan, ToolContext) -> Long) =
        Agent(this, RelayPrompt.PROMPT, listOf(CreateReminderTool(commit)), maxSteps = 3)
}
