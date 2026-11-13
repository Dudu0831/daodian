package com.abc.daodian.reminder.tools

import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 删提醒，一次可以删几条。闹钟、通知一起收，库里真删（同提醒列表左滑删，只是对话里没有「撤销」）。
 *
 * 结果第一行是给人看的「删了 2 条：喝水；交房租」，痕就画它（[ReminderTrace]）。
 */
class DeleteRemindersTool(private val desk: ReminderDesk) : Tool {

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "删掉已有的提醒（按 # 编号），一次可以删几条。只在用户明说不要这条了（「把喝水的提醒删了」「周五那个取消吧」「不用提醒我了」）时用；" +
            "只是改时间、改内容用 update_reminder。不知道编号先用 list_reminders 查；对得上的不止一条又说不清是哪条，用 ask_user 问。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "ids" to mapOf(
                "type" to "array",
                "description" to "要删的提醒 # 后面的数字：#12 填 12",
                "items" to mapOf("type" to "integer")
            )
        ),
        "required" to listOf("ids"),
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val ids = runCatching {
            mapper.readTree(arguments).get("ids")?.mapNotNull { n -> n.takeIf { it.canConvertToLong() }?.asLong() }
        }.getOrNull() ?: return ToolOutcome("没删。参数不是合法的 JSON：${arguments.take(200)}", ok = false)
        if (ids.isEmpty()) return ToolOutcome("没删。ids 是空的。", ok = false)

        val deleted = desk.delete(ids.distinct())
        val missing = ids.distinct() - deleted.map { it.id }.toSet()
        if (deleted.isEmpty()) return ToolOutcome("没删。没有 ${missing.joinToString("、") { "#$it" }} 这几条提醒，先用 list_reminders 查一下编号。", ok = false)
        val out = "删了 ${deleted.size} 条：" + deleted.joinToString("；") { it.title } +
            "\n" + deleted.joinToString("\n") { "#${it.id} ${it.title}" } +
            if (missing.isNotEmpty()) "\n没有这几条：${missing.joinToString("、") { "#$it" }}" else ""
        return ToolOutcome(out, ok = missing.isEmpty())
    }

    companion object {
        const val NAME = "delete_reminders"

        private val mapper = ObjectMapper()
    }
}
