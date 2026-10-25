package com.abc.daodian.runtime.model

/**
 * 「测一下」的结果。怎么测的见 `AgentRuntime.test`：发的就是聊天下一句真要发的那个请求
 */
sealed interface PingResult {
    data class Ok(val millis: Long) : PingResult
    data class Failed(val why: String, val raw: String) : PingResult
}
