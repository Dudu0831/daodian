package com.abc.daodian.harness.provider

import com.abc.daodian.harness.AgentEvent
import com.abc.daodian.harness.llm.LlmException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 上一次调模型的结果，给顶栏那枚印和它底下的纸签用。见 DESIGN.md 决策 8.4
 *
 * **只在内存里**，杀掉重开就回到 [Unknown]。存下来反而会误报 ——
 * 昨天晚上没网，今天早上打开 app 看见一枚灰印，其实网早好了。
 * 印章不是监控，它只说「我最近一次干活儿顺不顺」。
 */
sealed interface ApiState {

    /** 这次打开还没调用过。印章照常是朱砂 —— 未知当好的，别吓人 */
    data object Unknown : ApiState

    data object Ok : ApiState

    /**
     * @param why 人话，像「key 不对」。纸签顶上那条告警带的正文
     * @param raw 原始异常，排查用，小字垫在底下
     */
    data class Down(val why: String, val raw: String) : ApiState
}

object ApiHealth {

    private val _state = MutableStateFlow<ApiState>(ApiState.Unknown)
    val state: StateFlow<ApiState> = _state.asStateFlow()

    /**
     * 一轮的终局。
     *
     * **只有模型调用本身的失败才算数**（[LlmException]：网络、鉴权、服务端报错）。
     * 闸门拦下、用户不同意、模型跑偏都不改状态 —— 那是这句话的问题，不是这条链路的问题。
     * 用户点「停」走的是 CancellationException，压根到不了这儿。
     */
    fun record(end: AgentEvent) {
        when (end) {
            is AgentEvent.Finished -> _state.value = ApiState.Ok
            is AgentEvent.Failed -> if (end.cause is LlmException) recordDown(end.cause)
            else -> Unit
        }
    }

    fun recordDown(e: LlmException) {
        val raw = e.message.orEmpty()
        _state.value = ApiState.Down(humanize(raw, e), raw)
    }

    fun recordOk() {
        _state.value = ApiState.Ok
    }

    /** 换了配置：上一家的成绩不算到新一家头上 */
    fun reset() {
        _state.value = ApiState.Unknown
    }

    /**
     * 异常翻成人话。认不出来的就把异常类名摆出来 —— 宁可看着糙，也别猜错了误导人。
     */
    fun humanize(reason: String, cause: Throwable?): String {
        val text = (reason + " " + (cause?.cause?.message ?: "")).lowercase()
        return when {
            "401" in text || "unauthorized" in text || "invalid api key" in text -> "key 不对"
            "403" in text || "forbidden" in text -> "这个 key 没有权限"
            "404" in text || "not found" in text -> "网关地址或模型名不对"
            "429" in text || "rate limit" in text -> "太频繁，被限流了"
            "unknownhost" in text || "unable to resolve host" in text -> "连不上这个地址，先看看网"
            "timeout" in text || "timed out" in text -> "服务器没回，超时了"
            "connect" in text || "network" in text -> "网络不通"
            "500" in text || "502" in text || "503" in text || "504" in text -> "网关自己出错了"
            "模型侧失败" in reason || "流错误" in reason -> "服务端报错"
            else -> cause?.cause?.javaClass?.simpleName ?: "没连上"
        }
    }
}
