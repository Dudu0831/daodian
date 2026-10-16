package com.abc.daodian

import com.abc.daodian.assistant.AssistantAgent
import com.abc.daodian.assistant.memory.tidy.TidyAgent
import com.abc.daodian.ledger.organize.OrganizerAgent
import com.abc.daodian.reminder.relay.RelayAgent
import com.abc.daodian.runtime.AgentInfo

/**
 * 系统里一共这几个 agent（花名册）。加一个 = 写 XxxAgent（是谁、怎么建）+ XxxPrompt（说什么），再在这里加一行。
 * debug 包里跑一个不在这上面的 agent 直接崩（`AgentRuntime`）；导出诊断里按这个顺序列。见 DESIGN.md §6.1
 */
val AGENTS: List<AgentInfo> = listOf(
    AssistantAgent,  // 助手：对话页、桌面速记、每晚对账
    OrganizerAgent,  // 记账整理员：后台把支付通知整理成流水
    RelayAgent,      // 派活：名单上的人发来的话建成提醒
    TidyAgent        // 记忆整理员：后台抽记忆、写摘要
)
