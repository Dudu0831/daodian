package com.abc.daodian.runtime.context

import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn

/**
 * 什么时候把早先的对话压成摘要、压到哪一轮为止。见 DESIGN.md §6.9
 *
 * **成段地压，不逐轮滑**：闲下来（10 分钟没说话）压一次；连着聊不停的，原样喂的轮次攒到 [AT_TURNS] 轮
 * （或 [AT_CHARS] 个字）压一次。一压压到只剩最近 [KEEP] 轮，不满 [KEEP] 轮不压。
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

    /** 一张还原样带着的图按这么多字算。各家算法不一样，只是个量级 —— 图多了也该早点压 */
    const val IMAGE_CHARS = 800

    /** 压缩一直没成（断网、网关挂了）时的兜底：最多原样喂这么多轮，更早的就丢了 —— 跟以前的 LastTurns 一样 */
    const val HARD_CAP = 24

    /**
     * [turns] 是摘要之后、原样喂的那几轮（旧的在前）。该压了就返回压到哪一轮（含），不该压是 null。
     * 最后 [KEEP] 轮永远不压（受保护）：当前这一轮（可能还在跑、还在等问卡）一定在里面，
     * 他刚发的图、模型刚画的组件也都在这几轮里原样带着（[FoldImages]、[FoldDrawings] 留的是同样多轮）。
     * [idle] = 他已经闲下来了（10 分钟没说话）：不等攒够，[KEEP] 轮以前的都压 —— 隔一阵再开口，
     * 模型面前不该还摆着一长串上一个话头的原话。连着聊不停的时候才看 [AT_TURNS] / [AT_CHARS]。
     */
    fun cut(turns: List<Turn>, idle: Boolean = false, size: (List<Turn>) -> Int = ::chars): Long? {
        if (turns.size <= KEEP) return null
        if (!idle && turns.size < AT_TURNS && size(turns) < AT_CHARS) return null
        return turns[turns.size - KEEP - 1].id
    }

    /** 这几轮大约多少字：喂给模型的全部文字 */
    fun chars(turns: List<Turn>): Int = turns.sumOf { t ->
        t.items.sumOf {
            when (it) {
                is Item.UserMessage -> it.text.length + 30 + it.images.size * IMAGE_CHARS
                is Item.AssistantMessage -> it.text.length
                is Item.ToolCall -> it.name.length + it.arguments.length
                is Item.ToolResult -> it.output.length
            }
        }
    }
}
