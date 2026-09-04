package com.abc.daodian.ai

/**
 * 一轮对话历史，仅用于拼进下一次请求的提示词。
 *
 * 不编码 function_call/result 本身，但**建成的提醒要以一句中文回执留在历史里** ——
 * 原来整条丢掉，模型看到的是一连串没人应的用户请求，于是把同一件事反复建。
 * 回执写成人话而不是工具调用结构，是为了下一条：
 * 这样多轮对话在 CHAT_COMPLETIONS 和 RESPONSES 两种接口风格下都只需要拼一段文本，
 * 不依赖任何一家供应商特有的「服务端会话状态」（如 previous_response_id）——
 * 第三方 OpenAI 兼容接口很可能没实现那个。见 DESIGN.md §6.1
 */
data class ChatTurn(val fromUser: Boolean, val text: String)
