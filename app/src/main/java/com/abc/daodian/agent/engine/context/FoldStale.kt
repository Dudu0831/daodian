package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn

/**
 * 更早几轮里查询工具的结果（[com.abc.daodian.agent.engine.tool.Tool.outputGoesStale]，比如查账的明细表）
 * 折成一句话再喂回去：表又大，又是查的那一刻的样子，账后来改了模型还会照着旧表说。见 DESIGN.md §6.9
 *
 * 最近 [keep] 轮原样给 —— 他常接着上一句的结果说「第二笔是理发」，# 编号得还在。
 * 调用本身留着（结果得有对应的调用，不然网关拒），只换结果的字；短的不折，折了也省不了什么。
 * 只改喂给模型的，Session 里存的、界面上画的都不变。
 */
class FoldStale(
    private val tools: Set<String>,
    private val inner: ContextPolicy = LastTurns(),
    private val keep: Int = KEEP
) : ContextPolicy {

    override fun select(turns: List<Turn>): List<Turn> {
        val picked = inner.select(turns)
        if (tools.isEmpty()) return picked
        return picked.mapIndexed { i, turn -> if (i >= picked.size - keep) turn else fold(turn) }
    }

    private fun fold(turn: Turn): Turn {
        val stale = turn.items.filterIsInstance<Item.ToolCall>().filter { it.name in tools }.mapTo(HashSet()) { it.callId }
        fun foldable(item: Item) = item is Item.ToolResult && item.callId in stale && item.output.length > MIN_LENGTH
        if (turn.items.none(::foldable)) return turn
        return turn.copy(items = turn.items.map { if (foldable(it)) (it as Item.ToolResult).copy(output = FOLDED) else it })
    }

    companion object {
        /** 最近几轮不折（含当前轮） */
        const val KEEP = 3

        /** 比这短的不折 */
        const val MIN_LENGTH = 200

        const val FOLDED = "（查询结果，历史里省略了：那是当时的样子，要用就重新查）"
    }
}
