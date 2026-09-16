package com.abc.daodian.ai

/**
 * 按配置缓存解析器。
 *
 * 配置现在是可以随时改的（[ProviderStore]），解析器不能再在 ViewModel 里建一次就不管了。
 * 但也不该每句话都新建一个 —— 每个 [ToolCallParser] 自带一个 OkHttp 客户端，
 * 一句话一个连接池，白扔掉 keep-alive。所以按配置缓存一份：配置没变就一直是它。
 *
 * 对话页和桌面速记共用这里，配置一改两边同时换过去。
 */
object Parsers {

    private var cached: Pair<ProviderProfile, StreamingReminderParser>? = null

    @Synchronized
    fun of(profile: ProviderProfile): StreamingReminderParser {
        cached?.let { (p, parser) -> if (p == profile) return parser }
        return ToolCallParser(profile).also { cached = profile to it }
    }
}
