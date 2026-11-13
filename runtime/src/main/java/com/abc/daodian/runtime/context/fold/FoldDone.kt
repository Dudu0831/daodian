package com.abc.daodian.runtime.context.fold

import com.abc.daodian.runtime.context.ContextPolicy
import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.context.Compaction
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn

/**
 * 更早几轮里办完的事，结果只留头一行：建提醒、改账回来的那一大段办完就没用了；
 * 答完的问卡去掉末尾机读的那行（`answer=…`，是给界面重画问卡用的，模型用不上）。见 DESIGN.md §6.9
 *
 * 最近 [keep] 轮原样给。调用本身留着（结果得有对应的调用，不然网关拒）；短的不折。
 * [skip] 里的工具不碰 —— 查询结果归 [FoldStale]，它折得更早、整段换掉。
 * 只改喂给模型的，Session 里存的、界面上画的都不变。
 */
class FoldDone(
    private val skip: Set<String>,
    private val inner: ContextPolicy = LastTurns(),
    private val keep: Int = KEEP
) : ContextPolicy {

    override fun select(turns: List<Turn>): List<Turn> {
        val picked = inner.select(turns)
        return picked.mapIndexed { i, turn -> if (i >= picked.size - keep) turn else fold(turn) }
    }

    private fun fold(turn: Turn): Turn {
        val names = turn.items.filterIsInstance<Item.ToolCall>().associate { it.callId to it.name }
        var changed = false
        val items = turn.items.map { item ->
            if (item !is Item.ToolResult) return@map item
            val name = names[item.callId]
            val output = when {
                name in skip -> item.output
                name == AskUserTool.NAME -> item.output.lines().filterNot { it.startsWith("answer=") }.joinToString("\n")
                item.output.length > MIN_LENGTH -> item.output.lineSequence().first().take(HEAD) + TAIL
                else -> item.output
            }
            if (output == item.output) item else item.copy(output = output).also { changed = true }
        }
        return if (changed) turn.copy(items = items) else turn
    }

    companion object {
        /** 最近几轮不折（含当前轮） */
        const val KEEP = Compaction.KEEP

        /** 比这短的不折 */
        const val MIN_LENGTH = 200

        /** 折完留头一行的这么多字 */
        const val HEAD = 120

        const val TAIL = "…（办完了，当时回的后半截省略）"
    }
}
