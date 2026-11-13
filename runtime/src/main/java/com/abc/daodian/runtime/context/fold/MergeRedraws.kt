package com.abc.daodian.runtime.context.fold

import com.abc.daodian.runtime.context.ContextPolicy
import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn

/**
 * 画坏了、重画过的，喂给模型时两轮并成一轮：他的原话 + 第一回查到的东西 + 重画出来的那张。见 DESIGN.md §6.8、§6.9
 *
 * 画坏时 app 自己发起一轮让模型重画（开头是 [MARK]）。那一轮说完之后，画坏的那张和「刚才那张没画出来」这句
 * 就没用了，留在历史里模型只会照着跑偏的样子往下学 —— 手动「重试」会把失败那轮整轮抹掉，是同一个道理。
 * 重画那一轮还是最后一轮时不并：它正要看着那句错来改。
 *
 * 只改喂给模型的；[com.abc.daodian.runtime.engine.Session] 里存的、界面上画的都还是两轮。
 */
class MergeRedraws(private val inner: ContextPolicy = LastTurns()) : ContextPolicy {

    override fun select(turns: List<Turn>): List<Turn> = inner.select(merge(turns))

    companion object {
        /** 重画那一轮的开场白以它开头 */
        const val MARK = "刚才那张图没画出来"

        /** 重画那一轮的开场白：把错原样告诉模型 */
        fun request(error: String) = "$MARK（$error），改好重画一遍，写简单些。"

        fun merge(turns: List<Turn>): List<Turn> {
            if (turns.size < 3) return turns
            val out = ArrayList<Turn>(turns.size)
            var i = 0
            while (i < turns.size) {
                val turn = turns[i]
                val next = turns.getOrNull(i + 1)
                val redrawn = next != null && i + 1 != turns.lastIndex &&
                    next.input.trigger && next.input.text.startsWith(MARK) && next.items.size > 1
                if (redrawn) {
                    // 画坏的那几句拿掉，调过的工具和结果留着（重画用的数是那时查的）；顶着后一轮的编号
                    val kept = turn.items.drop(1).filterNot { it is Item.AssistantMessage && FoldDrawings.drawn(it.text) }
                    out += Turn(next!!.id, listOf(turn.input) + kept + next.items.drop(1))
                    i += 2
                } else {
                    out += turn
                    i++
                }
            }
            return out
        }
    }
}
