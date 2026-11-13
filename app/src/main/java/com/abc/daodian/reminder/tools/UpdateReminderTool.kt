package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.domain.Verdict
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 改一条已有的提醒：把改完的整条重写一遍（参数和 `create_reminder` 一样，前面多一个 id），和新建过同一道闸门 ——
 * 改到过去、改到 5 年以后照样拦下。没改的字段由模型照 `list_reminders` 那一行抄。
 *
 * 不做「只传改了的字段」：可空的字段（note、rrule）分不清「不改」和「改成空」，闸门也要整条才查得了。
 *
 * 成功时 [ToolOutcome.payload] 是 [Updated]，[ToolOutcome.ref] 是那条的 id（痕点进编辑页）。
 */
class UpdateReminderTool(private val desk: ReminderDesk) : Tool {

    data class Updated(val reminderId: Long, val plan: ReminderPlan)

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "改一条已有的提醒（按 # 编号）：改时间、改标题、改重复、改成轻提醒 / 响铃、改成当天事项都用它，不要删了再建。" +
            "不知道编号、不知道它现在的样子先用 list_reminders 查。把改完的整条写出来：没改的字段照 list_reminders 那一行竖线后面的值抄。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf<String, Any?>(
            "id" to mapOf("type" to "integer", "description" to "提醒 # 后面的数字：#12 填 12")
        ).apply { putAll(CreateReminderTool.PLAN_PROPERTIES) },
        "required" to listOf("id") + CreateReminderTool.PLAN_PROPERTIES.keys,
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val id = runCatching { mapper.readTree(arguments).get("id")?.takeIf { it.canConvertToLong() }?.asLong() }.getOrNull()
        val plan = CreateReminderTool.planOf(arguments)
        if (id == null || plan == null) return ToolOutcome("没改。参数不是合法的 JSON 或缺字段：${arguments.take(200)}", ok = false)

        return when (val verdict = PlanValidator.validate(plan, context.now)) {
            is Verdict.Ok -> {
                desk.update(id, verdict.plan, context)
                    ?: return ToolOutcome("没改。没有 #$id 这条提醒，先用 list_reminders 查一下编号。", ok = false)
                ToolOutcome(
                    output = "已改好（id=$id）：${CreateReminderTool.describe(verdict.plan)}",
                    ok = true,
                    ref = id,
                    payload = Updated(id, verdict.plan)
                )
            }
            is Verdict.Rejected ->
                ToolOutcome("没改。${verdict.reason} 照这个意思问用户一句，不要自己换个时间重试。", ok = false)
        }
    }

    companion object {
        const val NAME = "update_reminder"

        private val mapper = ObjectMapper()
    }
}
