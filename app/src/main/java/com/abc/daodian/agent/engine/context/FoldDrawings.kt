package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn

/**
 * 更早几轮里模型画过的图（```svg 代码块，或直接写的 <svg>…</svg>，见 DESIGN.md §6.11）折成一句话再喂回去：
 * 一张图一两千个 token，之后每句话都重发一遍不值。当前这一轮原样给 —— 它可能正接着自己刚画的图往下说。
 *
 * 只改喂给模型的；[com.abc.daodian.agent.engine.Session] 里存的、界面上画的都还是原样。
 * 没写完的图（没有 </svg>）不动 —— 认不准边界，宁可多花点 token。
 */
class FoldDrawings(private val inner: ContextPolicy = LastTurns()) : ContextPolicy {

    override fun select(turns: List<Turn>): List<Turn> {
        val picked = inner.select(turns)
        return picked.mapIndexed { i, turn ->
            if (i == picked.lastIndex || turn.items.none { it is Item.AssistantMessage && it.text.contains("<svg", ignoreCase = true) }) turn
            else turn.copy(items = turn.items.map { if (it is Item.AssistantMessage) Item.AssistantMessage(fold(it.text)) else it })
        }
    }

    companion object {
        /** 折完剩下的那句。写成旁白而不是图的样子 —— 免得模型以为这就是画图的写法、照着学 */
        const val FOLDED = "（这里原来有一张图，历史里省略了）"

        private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val FENCED = Regex("(`{3,}|~{3,})[ \\t]*(?:svg|xml|html)?[ \\t]*\\r?\\n\\s*(?:<\\?xml[^>]*>\\s*)?<svg\\b.*?</svg>\\s*\\1", OPTIONS)
        private val BARE = Regex("<svg\\b.*?</svg>", OPTIONS)

        fun fold(text: String): String = BARE.replace(FENCED.replace(text, FOLDED), FOLDED)
    }
}
