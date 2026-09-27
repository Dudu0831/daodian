package com.abc.daodian.agent.memory.tidy

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.context.FoldDrawings
import com.abc.daodian.agent.memory.Memory
import com.abc.daodian.agent.memory.MemoryRules
import com.abc.daodian.shared.format.Format
import java.time.Instant
import java.time.ZoneId

/**
 * 喂给整理员的那一大段：已经记下的、之前的摘要、这次要整理的对话（写成人读的记录，不是原样的结构）。
 * 会变的全在这里，整理员的 system（[TidyPrompt]）保持不动。
 */
object TidyText {

    /** 一次最多看这么多轮 / 这么多字；多了分几次，进度记在 tidy_runs */
    const val MAX_TURNS = 40
    const val MAX_CHARS = 30_000

    /** [turns] 从头取，取到超了 [MAX_TURNS] 或 [MAX_CHARS] 为止（至少一轮） */
    fun take(turns: List<Turn>, stale: Set<String>): List<Turn> {
        var chars = 0
        val out = mutableListOf<Turn>()
        for (t in turns) {
            chars += transcript(listOf(t), stale).length
            if (out.isNotEmpty() && (out.size >= MAX_TURNS || chars > MAX_CHARS)) break
            out += t
        }
        return out
    }

    fun input(
        memories: List<Memory>,
        digest: Digest?,
        turns: List<Turn>,
        compact: Boolean,
        stale: Set<String>,
        remember: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = buildString {
        append("已经记下的（${memories.size}/${MemoryRules.MAX_COUNT} 条）：\n")
        if (memories.isEmpty()) append("还没有\n")
        memories.forEach { append("m${it.id} · ${day(it.updatedAt, zone)} · ${it.text}\n") }

        if (compact) {
            append("\n之前的摘要：")
            if (digest == null) append("没有\n") else append("（到 ${day(digest.throughAt, zone)} 为止）\n${digest.text.trim()}\n")
        }

        val first = turns.first().input.at
        val last = turns.last().input.at
        append("\n这次的对话（${turns.size} 轮，${Format.humanDateTimeShort(first.toInstant().toEpochMilli(), zone)}")
        append(" – ${Format.humanDateTimeShort(last.toInstant().toEpochMilli(), zone)}）：\n")
        append(transcript(turns, stale, zone))

        append("\n这次要做：")
        append(
            when {
                !remember -> "只写摘要：把「之前的摘要」和这次的对话合成一段新摘要，填进 summary。他关掉了自动整理记忆，add、update、remove 都给空数组。"
                compact -> "整理记忆；另外把「之前的摘要」和这次的对话合成一段新摘要，填进 summary。"
                else -> "只整理记忆，summary 填 null。"
            }
        )
    }

    /**
     * 对话写成记录：`[9月24日 周三 21:03] 他：…`、`助手：…`，工具调用缩成一行。
     * 查询结果（[stale]）不抄 —— 那是当时账上的样子，不是关于他的事；问卡的答案要抄，那是他的原话。
     */
    fun transcript(turns: List<Turn>, stale: Set<String>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        turns.forEach { turn ->
            val names = turn.items.filterIsInstance<Item.ToolCall>().associate { it.callId to it.name }
            turn.items.forEach { item ->
                when (item) {
                    is Item.UserMessage -> {
                        val at = item.at.withZoneSameInstant(zone)
                        append("\n[${at.monthValue}月${at.dayOfMonth}日 ${Format.weekday(at.dayOfWeek)} %02d:%02d] ".format(at.hour, at.minute))
                        if (item.trigger) append("（app 自动发起，不是他说的）").append(clip(item.text, 300))
                        else append("他：").append(item.text)
                        append('\n')
                    }
                    is Item.AssistantMessage -> append("助手：").append(clip(FoldDrawings.fold(item.text), 800)).append('\n')
                    is Item.ToolCall -> append("  · 调 ${item.name}：").append(clip(item.arguments, 200)).append('\n')
                    is Item.ToolResult -> {
                        val name = names[item.callId]
                        val body = when (name) {
                            in stale -> "（查询结果，省略）"
                            AskUserTool.NAME -> clip(item.output.lines().filterNot { it.startsWith("answer=") }.joinToString(" "), 300)
                            else -> clip(item.output.lines().take(3).joinToString(" "), 160)
                        }
                        append(if (name == AskUserTool.NAME) "  · 他答：" else if (item.ok) "  · 成了：" else "  · 没成：")
                        append(body).append('\n')
                    }
                }
            }
        }
    }.trimStart('\n')

    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "…"

    private fun day(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).let { "${it.monthValue}月${it.dayOfMonth}日" }
}
