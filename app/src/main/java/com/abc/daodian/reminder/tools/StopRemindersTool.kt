package com.abc.daodian.reminder.tools

import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 「不再提醒」（DESIGN.md §4.6）：整串停掉，闹钟、通知一起收，记录留着（提醒页上是划掉的一行）。
 * 和删的分开：删是建错了、不要这条了；停是这件事不用再提醒了。也和「完成」分开：完成只算这一次。
 *
 * 结果第一行是给人看的「停了 2 条：喝水；吃药」，痕就画它（[ReminderTrace]）。
 */
class StopRemindersTool(private val desk: ReminderDesk) : Tool {

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "不再提醒（按 # 编号），一次可以停几条：整串停掉，以后都不响，记录留着。用户说「以后不用提醒我喝水了」「吃药那个停了吧」「药吃完了，别再提醒」时用。" +
            "他说的是这一次做完了（「喝过了」「交了」），不是停 —— 告诉他在通知或列表里点完成，那只算这一次。" +
            "不知道编号先用 list_reminders 查；对得上的不止一条又说不清是哪条，用 ask_user 问。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "ids" to mapOf(
                "type" to "array",
                "description" to "要停的提醒 # 后面的数字：#12 填 12",
                "items" to mapOf("type" to "integer")
            )
        ),
        "required" to listOf("ids"),
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val ids = runCatching {
            mapper.readTree(arguments).get("ids")?.mapNotNull { n -> n.takeIf { it.canConvertToLong() }?.asLong() }
        }.getOrNull()?.distinct() ?: return ToolOutcome("没停。参数不是合法的 JSON：${arguments.take(200)}", ok = false)
        if (ids.isEmpty()) return ToolOutcome("没停。ids 是空的。", ok = false)

        val stopped = desk.stop(ids)
        val missing = ids - stopped.map { it.id }.toSet()
        if (stopped.isEmpty()) {
            return ToolOutcome("没停。${missing.joinToString("、") { "#$it" }} 没有、或者已经不排着了，先用 list_reminders 查一下。", ok = false)
        }
        val out = "停了 ${stopped.size} 条：" + stopped.joinToString("；") { it.title } +
            "\n" + stopped.joinToString("\n") { "#${it.id} ${it.title}" } +
            if (missing.isNotEmpty()) "\n这几条没有、或者已经不排着了：${missing.joinToString("、") { "#$it" }}" else ""
        return ToolOutcome(out, ok = missing.isEmpty())
    }

    companion object {
        const val NAME = "stop_reminders"

        private val mapper = ObjectMapper()
    }
}
