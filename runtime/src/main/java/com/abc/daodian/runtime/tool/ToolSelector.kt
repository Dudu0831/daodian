package com.abc.daodian.runtime.tool

import com.abc.daodian.runtime.engine.Turn

/**
 * 每一步请求前，决定这一次把哪些工具的说明带给模型。和 [com.abc.daodian.runtime.context.ContextPolicy]
 * （带哪几轮）是一对：循环只问它，不知道背后是什么规矩。现在唯一的规矩是工具包（`kit/KitGate`）。
 *
 * 没带出去的工具还在循环手上，只是模型这一步看不见；它硬要调，循环不执行，把 [whyHidden] 回给它。
 */
interface ToolSelector {

    /** [all] 是这个 agent 手上全部的工具，[sent] 是这一步真要发出去的那几轮（折过的样子）。顺序要稳：前缀缓存靠它 */
    fun select(all: Collection<Tool>, sent: List<Turn>): Collection<Tool>

    /** 模型调了一个这一步没带出去的工具：回给它的话，写成它能照着往下接的样子 */
    fun whyHidden(name: String): String = "「$name」这个工具现在用不了。"

    companion object {
        /** 全带。没有工具包的 agent 都是它 */
        val ALL = object : ToolSelector {
            override fun select(all: Collection<Tool>, sent: List<Turn>): Collection<Tool> = all
        }
    }
}
