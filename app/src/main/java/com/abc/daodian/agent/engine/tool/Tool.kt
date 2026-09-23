package com.abc.daodian.agent.engine.tool

import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.ask.Asker
import java.time.ZonedDateTime

/**
 * 工具会不会改动用户的东西。写操作在对话里留一道「痕」（见 DESIGN.md §6.9），只读的不留。
 * 不再决定要不要先问用户 —— 写操作一律直接办，拿不准由模型自己用 `ask_user` 问。
 */
enum class ToolEffect { READ, WRITE }

/** 执行时能拿到的上下文 */
data class ToolContext(
    val now: ZonedDateTime,
    /** 这一轮用户的原话。落库时存成「来源」，出了问题能对照 */
    val userInput: String,
    /** 要问用户的时候找它（`ask_user` 用）。没有界面的地方是 [Asker.NONE] */
    val asker: Asker = Asker.NONE,
    /** 正在执行的这次调用 */
    val call: Item.ToolCall? = null,
    /** 这一轮用的模型。落库时记成「谁解析的」 */
    val model: String = ""
)

/**
 * 执行结果。
 *
 * @param output 回给模型看的文字。写成模型能照着往下接的样子：成了是什么，没成为什么、该怎么办
 * @param ok 这次调用有没有达成目的。界面用来区分「落印」和「× 没记下」
 * @param ref 办成后指向的那条记录的 id（比如新提醒）。随对话落盘，重启后重建卡片靠它
 * @param payload 给界面当场用的结构化结果，不落盘、模型看不到。具体类型由各工具自己说明
 */
data class ToolOutcome(val output: String, val ok: Boolean, val ref: Long? = null, val payload: Any? = null)

/**
 * 一个工具：自带 schema、自己执行。循环本身不认识任何具体工具。
 *
 * 实现要求：参数不合法、业务上拒绝都返回 `ok = false` 的 [ToolOutcome]，
 * 只有真正的意外才抛 —— 抛出来的会被循环兜成一条错误结果回给模型。
 */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema，`type: object` 那一层 */
    val parameters: Map<String, Any?>

    /** true 时服务端强制参数符合 schema。要求 required 覆盖全部字段、additionalProperties=false */
    val strict: Boolean get() = true

    val effect: ToolEffect

    /**
     * 这次调用没跑完就被停掉（喊停、页面关了、app 被杀）时记进历史的结果。
     * null = 用循环的通用说法（[com.abc.daodian.agent.engine.AgentLoop.ABORTED]）
     */
    val abortedOutput: String? get() = null

    suspend fun execute(arguments: String, context: ToolContext): ToolOutcome
}

class ToolRegistry(tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "工具重名：${tools.groupBy { it.name }.filterValues { it.size > 1 }.keys}" }
    }

    val all: Collection<Tool> get() = byName.values

    operator fun get(name: String): Tool? = byName[name]
}
