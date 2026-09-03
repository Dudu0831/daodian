package com.abc.daodian.ai

/**
 * 一轮对话历史，仅用于拼进下一次请求的提示词。
 *
 * 只记录纯文本往返（用户说的话 / 模型的反问），不编码工具调用本身 ——
 * 一旦模型调了工具，这个话题就有了结果，没必要把 function_call/result 也塞回历史里。
 * 这样多轮对话在 CHAT_COMPLETIONS 和 RESPONSES 两种接口风格下都只需要拼一段文本，
 * 不依赖任何一家供应商特有的「服务端会话状态」（如 previous_response_id）——
 * 第三方 OpenAI 兼容接口很可能没实现那个。见 DESIGN.md §6.1
 */
data class ChatTurn(val fromUser: Boolean, val text: String)
