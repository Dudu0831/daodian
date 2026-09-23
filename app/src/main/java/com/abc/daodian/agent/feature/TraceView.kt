package com.abc.daodian.agent.feature

/*
 * 痕：一次写操作在对话里留下的一行小字。见 DESIGN.md §6.9
 * 字怎么写归模块（[Feature.trace]），怎么画、点了怎么跳归 agent（agent/conversation/TraceLine.kt）。
 */

/** 痕的三种样子 */
enum class TraceState { RUNNING, OK, FAILED }

/** 写痕要用的：哪个工具、参数（流着的时候是半截）、办成没有、工具回给模型的话、办成后指向的记录 */
data class ToolTrace(
    val tool: String,
    val arguments: String,
    val state: TraceState,
    val output: String?,
    val ref: Long?
) {
    val ok: Boolean get() = state == TraceState.OK
    val failed: Boolean get() = state == TraceState.FAILED
}

/** 痕上要写的字。在办、办成、没办成三种状态用同一份规则，重建历史也用它 */
data class TraceView(
    /** 在办时的标签：「在记提醒」 */
    val working: String,
    /** 办成 / 没办成的标签：「提醒」「没建成」 */
    val settled: String,
    val text: String,
    /** 一次改了好几笔：点开逐笔看 */
    val lines: List<String> = emptyList(),
    /** 点了去哪（路由）。null = 点不了 */
    val route: String? = null
)

object TraceText {

    /** 结果的第一行去掉工具自己的前缀（「没建。」「没记。」），再截到第一句 */
    fun reasonOf(output: String?, vararg prefixes: String): String {
        var s = output?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        prefixes.forEach { s = s.removePrefix(it).trim() }
        // 模型自己把参数写坏了：原因里贴着半截 JSON，给人看没意义
        if (s.startsWith("参数不是合法的 JSON")) return "参数没写对"
        return s.substringBefore("照这个意思").substringBefore('。').trim().ifEmpty { "没办成" }
    }
}
