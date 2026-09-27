package com.abc.daodian.agent.memory

import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceText
import com.abc.daodian.agent.feature.TraceView
import com.abc.daodian.agent.shell.ShellRoutes

/**
 * 记忆的写操作在对话里留的痕：「✓ 记住了 · 『晚点』一般指晚上 9 点 ›」，点了去记忆页。见 DESIGN.md §6.6、§6.9
 *
 * 记忆不属于哪个模块（agent 自己的），所以不走 Feature.trace，由 TraceLine 先问它。
 */
object MemoryTrace {

    fun of(call: ToolTrace): TraceView? {
        if (call.tool != EditMemoryTool.NAME) return null
        // 结果除了第一行，一行一条：「+ m12 …」「~ m3 …」「- m5 …」
        val lines = call.output?.lines()?.drop(1)?.filter { it.length > 2 && it[0] in "+~-" }.orEmpty()
        val kinds = lines.map { it[0] }.toSet()
        val texts = lines.map { it.substring(2).substringAfter(' ') }
        return TraceView(
            working = "在记",
            settled = when {
                call.failed -> "没记成"
                kinds == setOf('-') -> "忘了"
                kinds == setOf('+') -> "记住了"
                else -> "改了记忆"
            },
            text = when {
                call.failed -> TraceText.reasonOf(call.output, "没记。")
                !call.ok -> firstAdded(call.arguments).orEmpty()
                texts.size == 1 -> texts.single()
                else -> "${texts.size} 条"
            },
            lines = if (call.ok && lines.size > 1) lines.map { mark(it[0]) + it.substring(2).substringAfter(' ') } else emptyList(),
            route = ShellRoutes.MEMORY.takeIf { call.ok }
        )
    }

    private fun mark(kind: Char) = when (kind) {
        '+' -> "记住 · "
        '~' -> "改成 · "
        else -> "忘了 · "
    }

    /** 流着的时候：从半截参数里抠第一条新记的 */
    private fun firstAdded(partial: String): String? {
        val at = partial.indexOf("\"add\"").takeIf { it >= 0 } ?: return null
        val bracket = partial.indexOf('[', at).takeIf { it >= 0 } ?: return null
        // 空数组后面紧跟的是下一个键名，不是记的内容
        val open = (bracket + 1 until partial.length).firstOrNull { !partial[it].isWhitespace() } ?: return null
        if (partial[open] != '"') return null
        val sb = StringBuilder()
        var i = open + 1
        while (i < partial.length) {
            val c = partial[i]
            if (c == '\\' && i + 1 < partial.length) { sb.append(partial[i + 1]); i += 2; continue }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }
}
