package com.abc.daodian.agent.memory.tidy

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome
import com.abc.daodian.agent.memory.Memory
import com.abc.daodian.agent.memory.MemoryApplied
import com.abc.daodian.agent.memory.MemoryBackend
import com.abc.daodian.agent.memory.MemoryEdit
import com.abc.daodian.agent.memory.MemoryJson
import com.abc.daodian.agent.memory.MemoryRules

/**
 * 整理员交作业：记忆怎么改 + （要压缩时）新摘要。只有后台整理带这个工具，对话里没有。
 *
 * 记忆当场落（过 [MemoryRules]；[remember] 为 false 时是设置里关了自动整理，交上来的记忆改动一概不收）；摘要不在这里落 —— 放进 [ToolOutcome.payload]，由 [Tidy] 连同进度一起记进 tidy_runs，
 * 进度和摘要要么一起有、要么一起没有。
 */
class SaveTidyTool(private val book: MemoryBackend, private val compact: Boolean, private val remember: Boolean = true) : Tool {

    /** 交上来的：落下的记忆改动和摘要（不压缩时是 null） */
    data class Saved(val applied: MemoryApplied, val summary: String?)

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description = "交上整理结果：记忆怎么改（add / update / remove），和要求写摘要时的新摘要（否则 null）。一次交全。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            *MemoryJson.EDIT_PROPERTIES,
            "summary" to mapOf(
                "type" to listOf("string", "null"),
                "description" to "要求写摘要时：接替之前摘要和这段对话的新摘要，${TidyPrompt.SUMMARY_MAX} 字以内。没要求就 null"
            )
        ),
        "required" to MemoryJson.EDIT_PROPERTIES.map { it.first } + "summary",
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = MemoryJson.parse(arguments) ?: return ToolOutcome("没收下：参数不是合法的 JSON。", ok = false)
        val edit = if (remember) MemoryJson.editOf(o) else MemoryEdit()
        val summary = o.get("summary")?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
        if (compact) {
            if (summary == null) return ToolOutcome("没收下：这次要写摘要，summary 不能是 null。", ok = false)
            if (summary.length > TidyPrompt.SUMMARY_MAX) {
                return ToolOutcome("没收下：摘要 ${summary.length} 字，太长了，压到 ${TidyPrompt.SUMMARY_MAX} 字以内。", ok = false)
            }
        }
        MemoryRules.problemOf(edit, book.all())?.let { return ToolOutcome("没收下：$it。改好再交。", ok = false) }
        val applied = if (edit.isEmpty) MemoryApplied() else book.apply(edit, Memory.TIDY)
        return ToolOutcome(
            "收下了：新记 ${applied.added.size} 条，改了 ${applied.updated.size} 条，删了 ${applied.removed.size} 条" +
                if (compact) "，摘要 ${summary?.length} 字。" else "。",
            ok = true,
            payload = Saved(applied, if (compact) summary else null)
        )
    }

    companion object {
        const val NAME = "save_tidy"
    }
}
