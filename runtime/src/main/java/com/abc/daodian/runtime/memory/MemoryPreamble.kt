package com.abc.daodian.runtime.memory

import com.abc.daodian.runtime.context.Preamble
import java.time.Instant
import java.time.ZoneId

/** 更早那段对话的摘要：盖住到第 [through] 轮（含），那一轮是 [throughAt] 说的 */
data class Digest(val text: String, val through: Long, val throughAt: Long)

/**
 * 垫在历史前面那段（DESIGN.md §6.9）怎么拼成一段话：记下的事 + 各模块的现状 + 更早对话的摘要。
 * 谁垫哪几样由用的一方定：对话页三样都垫、桌面速记没有摘要、派活只垫记忆（[com.abc.daodian.runtime.AgentRuntime.memory]）。
 */
object MemoryPreamble {

    /**
     * 摘要放最后：它后面紧接着就是原样喂的轮次。三样都没有就不垫
     *
     * @param modules 各模块的现状（模块接头的 `background`），自带标题
     */
    fun of(
        memories: List<Memory>,
        digest: Digest?,
        modules: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Preamble? {
        if (memories.isEmpty() && digest == null && modules.isEmpty()) return null
        val parts = buildList {
            if (memories.isNotEmpty()) {
                add(buildString {
                    append("你记得的关于他的事（m 编号 · 记下或改过的日子）：\n")
                    memories.forEach { append("- m${it.id} · ${day(it.updatedAt, zone)} · ${it.text}\n") }
                }.trimEnd())
            }
            modules.forEach { add(it.trim()) }
            if (digest != null) {
                add("更早的对话，摘要（到 ${day(digest.throughAt, zone)} 为止，之后的对话原样在下面）：\n" + digest.text.trim())
            }
        }
        return Preamble(parts.joinToString("\n\n"), digest?.through ?: 0)
    }

    private fun day(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).let { "${it.monthValue}月${it.dayOfMonth}日" }
}
