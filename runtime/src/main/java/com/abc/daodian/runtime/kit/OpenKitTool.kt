package com.abc.daodian.runtime.kit

import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome

/**
 * 打开一个工具包（[Kit]）：把它的说明回给模型。它带的工具下一步起出现在工具表里 —— 那是 [KitGate] 看历史办的，
 * 这里什么状态都不记，所以杀进程重开、换一个 agent 实例都不受影响。
 *
 * 目录（有哪些包、各自什么时候开）就写在这个工具的说明里，由各个包的 [Kit.summary] 拼出来：加一个包不用改提示词。
 * agent 手上有工具包时，runtime 自己把它加进工具（`AgentRuntime`），不用各 agent 去拼。只读，不留痕。
 */
class OpenKitTool(private val kits: List<Kit>) : Tool {

    init {
        require(kits.isNotEmpty())
        require(kits.map { it.name }.distinct().size == kits.size) { "工具包重名：${kits.map { it.name }}" }
    }

    override val name = NAME

    override val effect = ToolEffect.READ

    override val description =
        "有些不常用的工具平时收着，用到时先用它打开：打开后你会拿到那一组工具和它们的用法，接着就能办。" +
            "他要办的事对得上下面某一项就开，不要因为手上没看见工具就说做不到。打开过几轮会自动收起，还要用就再开一次。\n" +
            kits.joinToString("\n") { "- ${it.name}：${it.summary}" }

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf("name" to mapOf("type" to "string", "enum" to kits.map { it.name }, "description" to "要打开哪一个")),
        "required" to listOf("name"),
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val asked = kitOf(arguments)
        val kit = kits.firstOrNull { it.name == asked }
            ?: return ToolOutcome("没打开。没有叫「${asked.orEmpty()}」的，只有：${kits.joinToString("、") { it.name }}。", ok = false)
        val head = if (kit.tools.isEmpty()) "打开了「${kit.name}」。"
        else "打开了「${kit.name}」：${kit.tools.joinToString("、") { it.name }} 现在可以用了。"
        return ToolOutcome("$head\n\n${kit.guide}", ok = true)
    }

    companion object {
        const val NAME = "open_kit"

        private val NAME_ARG = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")

        /** 参数里要开的是哪个包。不是合法参数就是 null */
        fun kitOf(arguments: String): String? = NAME_ARG.find(arguments)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * 这几轮里成功打开过哪些包（[Item.ToolResult.ok]）。[KitGate] 和 [com.abc.daodian.runtime.context.fold.FoldKits]
         * 都靠它认，「开着」的规矩只有这一份
         */
        fun opened(turns: List<com.abc.daodian.runtime.engine.Turn>): Set<String> = turns.flatMapTo(LinkedHashSet()) { turn ->
            val ok = turn.items.filterIsInstance<Item.ToolResult>().filter { it.ok }.mapTo(HashSet()) { it.callId }
            turn.items.filterIsInstance<Item.ToolCall>().filter { it.name == NAME && it.callId in ok }.mapNotNull { kitOf(it.arguments) }
        }
    }
}
