package com.abc.daodian.runtime.context.fold

import com.abc.daodian.runtime.context.ContextPolicy
import com.abc.daodian.runtime.context.LastTurns
import com.abc.daodian.runtime.context.Compaction
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn

/**
 * 更早几轮里模型画过的东西（```html 小组件，以前的 ```svg / 直接写的 <svg>…</svg>，见 DESIGN.md §6.8）折成一句话再喂回去：
 * 一个组件几千个字，之后每句话都重发一遍不值。最近 [keep] 轮原样给 —— 他常接着说「把刚才那个改成按周」，
 * 这时候模型得还看得见自己写的代码。
 *
 * 折成的那句带着它画的是什么：模型在代码第一行写的注释（`<!-- 本月开销占比，点一项看明细 -->`，提示词里要求的）。
 * 每句都不一样，模型不容易把它当成一种写法照抄；整理员写摘要时也知道画过什么。
 *
 * 只改喂给模型的；[com.abc.daodian.runtime.engine.Session] 里存的、界面上画的都还是原样。
 * 没写完的（没有收尾围栏 / </svg>）不动 —— 认不准边界，宁可多花点 token。
 */
class FoldDrawings(private val inner: ContextPolicy = LastTurns(), private val keep: Int = KEEP) : ContextPolicy {

    override fun select(turns: List<Turn>): List<Turn> {
        val picked = inner.select(turns)
        return picked.mapIndexed { i, turn ->
            if (i >= picked.size - keep || turn.items.none { it is Item.AssistantMessage && MAYBE.containsMatchIn(it.text) }) turn
            else turn.copy(items = turn.items.map { if (it is Item.AssistantMessage) Item.AssistantMessage(fold(it.text)) else it })
        }
    }

    companion object {
        /** 最近几轮画的原样带（含当前轮）。和压缩保护的轮数一样（[Compaction.KEEP]） */
        const val KEEP = Compaction.KEEP

        private const val TAIL = "代码在历史里省略了。这句是 app 写的旁白，不是画图的写法：要再画，重新写完整的 html 代码块）"

        /** 没写标题的（旧的 svg 图）折成这句 */
        const val FOLDED = "（你在这里画过一张图，$TAIL"

        /**
         * 折出来的每一句都有这半句。模型照抄旁白时界面靠它认（11-13 真机上见过：前面几轮的图都折成了同一句，
         * 它以为这就是画图，回答里原样写了一句，图没画）—— 正文里有它就当成画坏了，让模型重画
         */
        const val MARK = "你在这里画过一"

        /** 标题最长这么多字 */
        private const val TITLE_MAX = 40

        fun folded(title: String?): String = if (title.isNullOrBlank()) FOLDED else "（你在这里画过一个组件：$title。$TAIL"

        private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val FENCED = Regex("(`{3,}|~{3,})[ \\t]*(?:svg|xml|html)?[ \\t]*\\r?\\n\\s*(?:<\\?xml[^>]*>\\s*)?<svg\\b.*?</svg>\\s*\\1", OPTIONS)
        private val BARE = Regex("<svg\\b.*?</svg>", OPTIONS)

        /** 会动的小组件（```html 代码块）整块折掉，只认写了收尾围栏的 */
        private val WIDGET = Regex("(`{3,}|~{3,})[ \\t]*html[ \\t]*\\r?\\n.*?\\n[ \\t]*\\1", OPTIONS)
        private val MAYBE = Regex("<svg|```html|~~~html", RegexOption.IGNORE_CASE)
        private val COMMENT = Regex("<!--\\s*(.*?)\\s*-->", RegexOption.DOT_MATCHES_ALL)

        /** 这段话里有没有画的东西（写完没写完都算），或者照抄了旁白 */
        fun drawn(text: String): Boolean = MAYBE.containsMatchIn(text) || MARK in text

        /** 代码开头那句注释。只看最前面一小段：写在中间的注释是给代码看的，不是标题 */
        private fun titleOf(block: String): String? =
            COMMENT.find(block.take(400))?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(TITLE_MAX)?.ifBlank { null }

        fun fold(text: String): String {
            val each: (MatchResult) -> String = { folded(titleOf(it.value)) }
            return BARE.replace(FENCED.replace(WIDGET.replace(text, each), each), each)
        }
    }
}
