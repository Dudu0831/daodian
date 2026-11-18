package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.reminder.domain.ReminderText
import com.abc.daodian.reminder.domain.Verdict
import com.abc.daodian.reminder.domain.spec
import com.abc.daodian.reminder.tools.CreateReminderTool.Companion.text
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 改一条已有的提醒：模型只填要改的那几项（null = 不改），这里和库里那条现在的样子合成整条，再过一遍和新建一样的闸门 ——
 * 改到过去、改到 5 年以后照样拦下。11-17 以前是让模型把整条照 `list_reminders` 抄一遍，抄丢字段是常事。
 *
 * 能清空的两项（note、rrule）用空字符串表示「去掉」，和 null（不改）分开。日子没改的，从 [ListRemindersTool.listedDay] 取
 * （拖着的那条日子已经过了，原样交回去会被闸门拦）。跟不跟时区走：钟点和重复都没改就照旧，改了就照新的推（[CreateReminderTool.anchoredOf]）。
 *
 * 成功时 [ToolOutcome.payload] 是 [Updated]，[ToolOutcome.ref] 是那条的 id（痕点进编辑页）。
 */
class UpdateReminderTool(private val desk: ReminderDesk) : Tool {

    data class Updated(val reminderId: Long, val spec: ReminderSpec)

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "改一条已有的提醒（按 # 编号）：日子、钟点、标题、重复、提前提醒、怎么响、催不催都用它，不要删了再建。" +
            "只填要改的，别的填 null。不知道编号先用 list_reminders 查。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf<String, Any?>(
            "id" to mapOf("type" to "integer", "description" to "提醒 # 后面的数字：#12 填 12")
        ).apply { putAll(CreateReminderTool.planProperties(patch = true)) },
        "required" to listOf("id") + CreateReminderTool.PLAN_PROPERTIES.keys,
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = runCatching { mapper.readTree(arguments) }.getOrNull()?.takeIf { it.isObject }
            ?: return ToolOutcome("没改。参数不是合法的 JSON：${arguments.take(200)}", ok = false)
        val id = o.get("id")?.takeIf { it.canConvertToLong() }?.asLong()
            ?: return ToolOutcome("没改。id 要填提醒 # 后面的数字。", ok = false)
        val now = desk.all().firstOrNull { it.id == id }
            ?: return ToolOutcome("没改。没有 #$id 这条提醒，先用 list_reminders 查一下编号。", ok = false)

        val plan = merge(now, o, context)
        return when (val verdict = PlanValidator.validate(plan, context.now)) {
            is Verdict.Ok -> {
                desk.update(id, verdict.spec, context)
                    ?: return ToolOutcome("没改。没有 #$id 这条提醒，先用 list_reminders 查一下编号。", ok = false)
                ToolOutcome(
                    output = "已改好（id=$id）：${ReminderText.describe(verdict.spec, context.now.toLocalDate())}",
                    ok = true,
                    ref = id,
                    payload = Updated(id, verdict.spec)
                )
            }
            is Verdict.Rejected -> CreateReminderTool.rejected("没改。", verdict)
        }
    }

    companion object {
        const val NAME = "update_reminder"

        private val mapper = ObjectMapper()

        /** 库里那条现在的样子 + 这次要改的几项 → 整条，交闸门 */
        fun merge(r: Reminder, o: JsonNode, context: ToolContext): ReminderPlan {
            val was = r.spec()
            val zone = context.now.zone
            val wasTimes = was.times.map { it.toString().take(5) }
            val times = CreateReminderTool.timesOf(o) ?: wasTimes
            /** null = 不改，"" = 去掉 */
            fun clearable(field: String, old: String?): String? {
                val n = o.get(field) ?: return old
                if (n.isNull) return old
                return n.asText().trim().takeIf { it.isNotEmpty() }
            }
            val rrule = clearable("rrule", was.rrule)
            val anchored = if (times == wasTimes && rrule == was.rrule) was.wallClockAnchored else CreateReminderTool.anchoredOf(rrule, times)
            return ReminderPlan(
                title = o.text("title") ?: was.title,
                note = clearable("note", was.note),
                basis = o.text("basis").orEmpty(),
                day = o.text("day") ?: ListRemindersTool.listedDay(r, zone, context.now.toLocalDate()).toString(),
                times = times,
                rrule = rrule,
                remindBefore = CreateReminderTool.remindBeforeOf(o) ?: was.remindBefore,
                ring = o.text("ring") ?: was.ring.name.lowercase(),
                nag = o.text("nag") ?: was.nag.name.lowercase(),
                wallClockAnchored = anchored
            )
        }
    }
}
