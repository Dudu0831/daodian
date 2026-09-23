package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Turn

/**
 * 每一步请求前，决定把哪些轮次喂给模型。
 *
 * 裁剪只能按**整轮**来：从一轮中间切开，会切出没有 function_call 的 function_call_output，
 * 网关直接拒。当前这一轮永远是最后一轮，任何策略都不许把它丢掉。
 */
fun interface ContextPolicy {
    fun select(turns: List<Turn>): List<Turn>
}

/** 最简单的一种：只留最近 [n] 轮（含当前轮）。以后的摘要压缩、按 token 预算裁剪都是换一个实现 */
class LastTurns(private val n: Int = DEFAULT) : ContextPolicy {

    init {
        require(n >= 1) { "至少要留当前这一轮" }
    }

    override fun select(turns: List<Turn>): List<Turn> = turns.takeLast(n)

    companion object {
        const val DEFAULT = 10
    }
}
