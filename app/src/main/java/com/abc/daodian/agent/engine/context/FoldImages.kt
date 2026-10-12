package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn

/**
 * 更早几轮里他发来的图片折成一句话再喂回去：图是每一步都要把文件重传一遍的，一张还顶几百上千个 token。
 * 最近 [keep] 轮原样给 —— 他常接着上一张图说「不对，是右下角那个数」，这时候模型得还看得见。见 DESIGN.md §6.10
 *
 * 没等到回应就过去了的那一轮（只剩他那一句：失败了、没重试），图也折掉，不管新旧 ——
 * 模型看不了图的时候，带图的那句被网关退回来，要是下一句还原样带着它，就句句都被退。
 *
 * 只改喂给模型的；[com.abc.daodian.agent.engine.Session] 里存的、界面上画的都还是原样，图一直在对话里。
 */
class FoldImages(private val inner: ContextPolicy = LastTurns(), private val keep: Int = KEEP) : ContextPolicy {

    override fun select(turns: List<Turn>): List<Turn> {
        val picked = inner.select(turns)
        return picked.mapIndexed { i, turn ->
            val recent = i >= picked.size - keep
            val unanswered = i != picked.lastIndex && turn.items.size == 1
            if (turn.input.images.isEmpty() || (recent && !unanswered)) turn
            else turn.copy(items = turn.items.map { if (it is Item.UserMessage) fold(it) else it })
        }
    }

    companion object {
        /** 最近几轮的图原样带（含当前轮） */
        const val KEEP = 3

        /** 折完剩下的那句以它开头。调试记录靠它数折了几句 */
        const val MARK = "（这里原来有"

        fun folded(count: Int) = "$MARK $count 张图片，历史里省略了；要再看得请他重发）"

        /** 图拿掉，原话后面接一句交代。只发了图的那句就只剩这句交代 */
        fun fold(message: Item.UserMessage): Item.UserMessage =
            if (message.images.isEmpty()) message
            else message.copy(
                text = listOf(message.text, folded(message.images.size)).filter { it.isNotBlank() }.joinToString("\n"),
                images = emptyList()
            )
    }
}
