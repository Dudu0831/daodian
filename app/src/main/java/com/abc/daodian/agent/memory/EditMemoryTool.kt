package com.abc.daodian.agent.memory

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome

/**
 * 他在对话里说「记住…」「别记那个了」时，模型当场记、当场忘。写操作，留痕（[MemoryTrace]）。见 DESIGN.md §6.9
 *
 * 他没要求的不靠它记 —— 那是后台整理的活（`tidy/`），不拖慢这一轮。
 */
class EditMemoryTool(private val book: MemoryBackend) : Tool {

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "记住或忘掉关于用户的事。只在他明确要你记、要你忘、或者纠正你记错的时候用（「记住我周二晚上健身」「别记那个了」）。" +
            "记的是他这个人：作息、他的说法指什么、偏好、常提的人和地方。某一条提醒、某一笔账不往这里记，它们有自己的工具；" +
            "但以后认账用得上的说法要记（「尾号 8837 的卡是老婆在用」「每月 5 号建行扣的 3500 是房租」），后台整理账目的也读记忆。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(*MemoryJson.EDIT_PROPERTIES),
        "required" to MemoryJson.EDIT_PROPERTIES.map { it.first },
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = MemoryJson.parse(arguments) ?: return ToolOutcome("没记。参数不是合法的 JSON。", ok = false)
        val edit = MemoryJson.editOf(o)
        if (edit.isEmpty) return ToolOutcome("没记。add、update、remove 都是空的。", ok = false)
        MemoryRules.problemOf(edit, book.all())?.let { return ToolOutcome("没记。$it。", ok = false) }
        val applied = book.apply(edit, Memory.SAID)
        if (applied.count == 0) return ToolOutcome("没记。要改、要删的那几条刚刚已经不在了。", ok = false)
        return ToolOutcome(
            (listOf("记好了。") + applied.lines()).joinToString("\n"),
            ok = true,
            ref = (applied.added + applied.updated).firstOrNull()?.id,
            payload = applied
        )
    }

    companion object {
        const val NAME = "edit_memory"
    }
}
