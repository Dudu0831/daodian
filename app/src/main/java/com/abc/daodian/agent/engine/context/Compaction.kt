package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn

/**
 * 什么时候把早先的对话压成摘要、压到哪一轮为止。见 DESIGN.md §6.9
 *
 * **成段地压，不逐轮滑**：原样喂的轮次攒到 [AT_TURNS] 轮（或 [AT_CHARS] 个字）才压一次，一压压到只剩最近 [KEEP] 轮。
 * 两次压缩之间，喂给模型的前缀（背景 + 那几轮）一个字不变，前缀缓存吃得上；
 * 每轮往前滑一格的话，开头那轮每次都换，缓存只剩 system 和工具。
 *
 * 只按整轮切，理由同 [ContextPolicy]。
 */
object Compaction {

    /** 摘要之后原样喂的攒到这么多轮就压 */
    const val AT_TURNS = 16

    /** 或者字数（折过图和旧查询之后）到这么多就压。中文一个字大约一个 token */
    const val AT_CHARS = 24_000

    /** 压完留几轮原样喂（含当前轮） */
    const val KEEP = 6

    /** 压缩一直没成（断网、网关挂了）时的兜底：最多原样喂这么多轮，更早的就丢了 —— 跟以前的 LastTurns 一样 */
    const val HARD_CAP = 24

    /**
     * [turns] 是摘要之后、原样喂的那几轮（旧的在前）。该压了就返回压到哪一轮（含），不该压是 null。
     * 最后 [KEEP] 轮永远不压：当前这一轮（可能还在跑、还在等问卡）一定在里面。
     */
    fun cut(turns: List<Turn>, size: (List<Turn>) -> Int = ::chars): Long? {
        if (turns.size <= KEEP) return null
        if (turns.size < AT_TURNS && size(turns) < AT_CHARS) return null
        return turns[turns.size - KEEP - 1].id
    }

    /** 这几轮大约多少字：喂给模型的全部文字 */
    fun chars(turns: List<Turn>): Int = turns.sumOf { t ->
        t.items.sumOf {
            when (it) {
                is Item.UserMessage -> it.text.length + 30
                is Item.AssistantMessage -> it.text.length
                is Item.ToolCall -> it.name.length + it.arguments.length
                is Item.ToolResult -> it.output.length
            }
        }
    }
}
