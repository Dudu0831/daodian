package com.abc.daodian.ai

import java.time.ZonedDateTime

/** 「测一下」的结果 */
sealed interface PingResult {
    data class Ok(val millis: Long) : PingResult
    data class Failed(val why: String, val raw: String) : PingResult
}

/**
 * 测一下这份配置能不能用。
 *
 * 走的就是**真正那条路**：同一个 [ToolCallParser]、同一把 key、同一个模型名，
 * 只是换成一句最短的话。不另外拿 `/models` 之类的接口试探 ——
 * 第三方网关不一定实现它，测通了也不代表正式调用能通，那种「绿灯」比没有还坏。
 *
 * 测的是**传进来的**配置，不是已保存的：配置页里改完能先测再存。
 */
object ProviderTest {

    suspend fun run(profile: ProviderProfile): PingResult {
        if (!profile.isConfigured) {
            return PingResult.Failed("三格还没填全", profile.redacted())
        }
        val started = System.currentTimeMillis()
        val result = Parsers.of(profile).parse("你好", ZonedDateTime.now())
        val elapsed = System.currentTimeMillis() - started

        return when (result) {
            // 反问也好、真给我建了条提醒也好，都说明这条链路是通的
            is ParseResult.Ok, is ParseResult.NeedsClarification -> {
                ApiHealth.recordOk()
                PingResult.Ok(elapsed)
            }
            is ParseResult.Failed -> {
                ApiHealth.record(result)
                PingResult.Failed(ApiHealth.humanize(result), result.reason)
            }
        }
    }
}
