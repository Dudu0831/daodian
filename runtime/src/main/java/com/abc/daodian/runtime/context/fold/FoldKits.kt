package com.abc.daodian.runtime.context.fold

import com.abc.daodian.runtime.context.ContextPolicy
import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.kit.KitGate
import com.abc.daodian.runtime.kit.OpenKitTool

/**
 * 更早几轮里打开工具包拿到的说明，折成一句「收起来了」：那一大段用法过了 [keep] 轮多半用不上了，
 * 工具也在同一刻从工具表里撤掉（[KitGate]）—— 折成这句是让模型知道要用得再开，不是照着旧说明去调已经不在的工具。
 *
 * 调用本身留着（结果得有对应的调用，不然网关拒）。只改喂给模型的，Session 里存的不变。
 */
class FoldKits(private val inner: ContextPolicy = LastTurns(), private val keep: Int = KitGate.KEEP) : ContextPolicy {

    override fun select(turns: List<Turn>): List<Turn> {
        val picked = inner.select(turns)
        return picked.mapIndexed { i, turn -> if (i >= picked.size - keep) turn else fold(turn) }
    }

    private fun fold(turn: Turn): Turn {
        val opens = turn.items.filterIsInstance<Item.ToolCall>().filter { it.name == OpenKitTool.NAME }.associateBy { it.callId }
        if (opens.isEmpty()) return turn
        return turn.copy(items = turn.items.map { item ->
            val call = (item as? Item.ToolResult)?.let { opens[it.callId] } ?: return@map item
            (item as Item.ToolResult).copy(output = folded(OpenKitTool.kitOf(call.arguments).orEmpty()))
        })
    }

    companion object {
        fun folded(kit: String) = "（当时打开过工具包「$kit」，现在收起来了：还要用就再 ${OpenKitTool.NAME} 一次）"
    }
}
