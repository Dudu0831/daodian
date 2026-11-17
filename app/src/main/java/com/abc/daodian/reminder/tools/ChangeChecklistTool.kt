package com.abc.daodian.reminder.tools

import com.abc.daodian.reminder.domain.Checklist
import com.abc.daodian.reminder.domain.ChecklistEdit
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 改一条提醒的清单（§4.5）：几号勾上、几号取消、加几样、删几号。号是 `list_reminders` 在那一条底下列的顺序。
 *
 * 不并进 `update_reminder`：那边是整条重写，清单要是也整份重写，模型抄漏一个勾就把你勾过的冲掉了。
 * 成功时 [ToolOutcome.payload] 是 [Changed]，[ToolOutcome.ref] 是那条的 id（痕点进编辑页）。
 */
class ChangeChecklistTool(private val desk: ReminderDesk) : Tool {

    data class Changed(val reminderId: Long, val title: String, val plan: ChecklistEdit.Plan)

    override val name = NAME

    override val effect = ToolEffect.WRITE

    override val description =
        "改一条提醒底下的清单：他说「鸡蛋买了」「行李里加个充电宝」「雨伞不带了」「清单清一下」就用它。" +
            "先用 list_reminders 查到 # 编号和清单上每一样的号，再填要勾的、要取消的、要加的、要删的；不动的不用写。"

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to linkedMapOf(
            "id" to mapOf("type" to "integer", "description" to "提醒 # 后面的数字"),
            "check" to numbers("勾上的几样的号（买了、带上了、办好了）"),
            "uncheck" to numbers("取消勾的号（勾错了、又要了）"),
            "add" to mapOf(
                "type" to "array", "items" to mapOf("type" to "string"),
                "description" to "新加的几样，每样最多 ${Checklist.MAX_CHARS} 个字"
            ),
            "remove" to numbers("删掉的号（不要了；「清单清一下」是把勾了的都删掉）")
        ),
        "required" to listOf("id", "check", "uncheck", "add", "remove"),
        "additionalProperties" to false
    )

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome {
        val o = runCatching { mapper.readTree(arguments) }.getOrNull()
        val id = o?.get("id")?.takeIf { it.canConvertToLong() }?.asLong()
            ?: return ToolOutcome("没改。参数不是合法的 JSON 或缺字段：${arguments.take(200)}", ok = false)
        val edit = ChecklistEdit(
            check = o.ints("check"), uncheck = o.ints("uncheck"),
            add = o.path("add").takeIf { it.isArray }?.mapNotNull { it.asText() }.orEmpty(),
            remove = o.ints("remove")
        )
        Checklist.problemOf(edit.add)?.let { return ToolOutcome("没改。$it 这是参数填错了，改好再调一次，不用问用户。", ok = false) }

        val title = desk.all().firstOrNull { it.id == id }?.title
            ?: return ToolOutcome("没改。没有 #$id 这条提醒，先用 list_reminders 查一下编号。", ok = false)
        val (plan, now) = desk.editItems(id, edit)
            ?: return ToolOutcome("没改。没有 #$id 这条提醒，先用 list_reminders 查一下编号。", ok = false)
        if (plan.empty) {
            val why = if (plan.unknown.isNotEmpty()) "清单上没有 ${plan.unknown.joinToString("、")} 号，先用 list_reminders 看一下现在的清单。"
            else "要勾的已经勾了、要取消的本来就没勾，清单没变。"
            return ToolOutcome("没改。$why", ok = false)
        }
        val out = listOfNotNull(
            "勾了 ${plan.checked.size} 样：${plan.checked.joinToString("、") { it.text }}".takeIf { plan.checked.isNotEmpty() },
            "取消了 ${plan.unchecked.size} 样：${plan.unchecked.joinToString("、") { it.text }}".takeIf { plan.unchecked.isNotEmpty() },
            "加了 ${plan.added.size} 样：${plan.added.joinToString("、")}".takeIf { plan.added.isNotEmpty() },
            "删了 ${plan.removed.size} 样：${plan.removed.joinToString("、") { it.text }}".takeIf { plan.removed.isNotEmpty() },
            "清单满了（最多 ${Checklist.MAX_ITEMS} 样），没加进去：${plan.dropped.joinToString("、")}".takeIf { plan.dropped.isNotEmpty() },
            "没有 ${plan.unknown.joinToString("、")} 号，那几个没动".takeIf { plan.unknown.isNotEmpty() }
        ).joinToString("；")
        val left = Checklist.left(now)
        return ToolOutcome(
            output = "已改好「$title」（id=$id）的清单：$out。" + if (now.isEmpty()) "清单空了。" else if (left == 0) "都勾完了。" else "还剩 $left 样。",
            ok = true,
            ref = id,
            payload = Changed(id, title, plan)
        )
    }

    companion object {
        const val NAME = "change_checklist"

        private val mapper = ObjectMapper()

        private fun numbers(desc: String) = mapOf("type" to "array", "items" to mapOf("type" to "integer"), "description" to desc)

        private fun JsonNode.ints(field: String): List<Int> =
            path(field).takeIf { it.isArray }?.mapNotNull { n -> n.takeIf { it.canConvertToInt() }?.asInt() }.orEmpty()
    }
}
