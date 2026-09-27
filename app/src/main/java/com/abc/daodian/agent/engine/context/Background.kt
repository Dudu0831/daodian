package com.abc.daodian.agent.engine.context

import com.abc.daodian.agent.engine.Session

/**
 * 历史前面垫的一段：关于用户记下的事、更早那段对话的摘要。见 DESIGN.md §6.9
 *
 * 为什么不放进 system：system 要逐字节稳定（前缀缓存，§6.3），这段会变。垫在历史最前面，
 * 两次变动之间前缀照样不变。喂给模型时是第一条消息，开头打 [MARK]，提示词里交代过它不是用户说的。
 *
 * @param text 垫的内容
 * @param afterTurnId 摘要已经盖住了哪一轮：这一轮及以前的不再原样喂。没有摘要是 0
 */
data class Preamble(val text: String, val afterTurnId: Long = 0) {
    companion object {
        const val MARK = "（app 附上的背景，不是用户说的）"
    }
}

/** 每一步请求前问它要垫什么。null = 不垫、历史全给（交给 [ContextPolicy] 裁） */
fun interface Background {
    suspend fun of(session: Session): Preamble?

    companion object {
        val NONE = Background { null }
    }
}
