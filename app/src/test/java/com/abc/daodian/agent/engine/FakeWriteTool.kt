package com.abc.daodian.agent.engine

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome

/**
 * agent 测试用的写工具，不依赖任何模块。参数 `{"title": "…"}`，带 `"reject":true` 就拒收（像闸门拦下）。
 * 办成时交给 [commit]，它返回的 id 当作记录的 ref。
 */
class FakeWriteTool(private val commit: (String) -> Long) : Tool {

    override val name = NAME
    override val description = "测试用的写工具"
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf("title" to mapOf("type" to "string")),
        "required" to listOf("title"),
        "additionalProperties" to false
    )
    override val effect = ToolEffect.WRITE

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(arguments)?.groupValues?.get(1)
            ?: return ToolOutcome("没建。参数不对：$arguments", ok = false)
        if (Regex("\"reject\"\\s*:\\s*true").containsMatchIn(arguments)) {
            return ToolOutcome("没建。时间已经过了。照这个意思问用户一句。", ok = false)
        }
        val id = commit(title)
        return ToolOutcome("已建好（id=$id）：「$title」", ok = true, ref = id)
    }

    companion object {
        const val NAME = "fake_write"
    }
}
