package com.abc.daodian.reminder.tools

import com.abc.daodian.assistant.plugin.PartialArgs
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceText
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.reminder.domain.ReminderText

/**
 * 提醒在对话里留的痕：建的「✓ 提醒 9月24日 周四 08:00 · 带伞 ›」、改的「✓ 提醒 改成 明天 …」，点了去编辑页；
 * 删的「✓ 提醒 删了 · 喝水」、停的「✓ 提醒 不再提醒 · 吃药」，点不进去。见 DESIGN.md §6.6
 *
 * 轻提醒的多一截，写在标题前面：「每天 14:00 · 轻提醒 · 喝水」—— 痕只有一行，标题长了被截断时它还在。
 */
object ReminderTrace {

    fun of(call: ToolTrace): TraceView? = when (call.tool) {
        CreateReminderTool.NAME -> plan(call, working = "在记提醒", failed = "没建成", prefix = "没建。")
        UpdateReminderTool.NAME -> plan(call, working = "在改提醒", failed = "没改成", prefix = "没改。", verb = "改成")
        ChangeChecklistTool.NAME -> checklist(call)
        StopRemindersTool.NAME -> listed(call, working = "在停提醒", failed = "没停成", prefix = "没停。", verb = "不再提醒")
        DeleteRemindersTool.NAME -> listed(call, working = "在删提醒", failed = "没删成", prefix = "没删。", verb = "删了")
        else -> null
    }

    /** 建和改：写的都是这一条现在什么时候响。改的多「改成」两个字，点了一样去编辑页 */
    private fun plan(call: ToolTrace, working: String, failed: String, prefix: String, verb: String? = null): TraceView {
        // 11-16 以前的旧参数认不得，只写标题
        val spec = if (call.ok) CreateReminderTool.planOf(call.arguments)?.let(PlanValidator::read) else null
        // 建的时候顺手列的清单（§4.5）：痕上只写几样，细目逐样列
        val items = if (call.ok && verb == null) CreateReminderTool.itemsOf(call.arguments) else emptyList()
        val text = when {
            call.failed -> TraceText.reasonOf(call.output, prefix)
            spec != null -> (verb?.let { "$it " } ?: "") +
                listOfNotNull(
                    ReminderText.whenOf(spec), "轻提醒".takeIf { spec.ring == Ring.NOTICE && spec.times.isNotEmpty() },
                    "提前提醒 ${spec.remindBefore.size} 次".takeIf { spec.remindBefore.isNotEmpty() }, spec.title,
                    "清单 ${items.size} 样".takeIf { items.isNotEmpty() }
                ).joinToString(" · ")
            else -> PartialArgs.text(call.arguments, "title").orEmpty()
        }
        return TraceView(
            working = working,
            settled = if (call.failed) failed else "提醒",
            text = text,
            // 细目：这一条一行，清单一行逐样列
            lines = if (items.isEmpty()) emptyList() else listOf(text.substringBeforeLast(" · 清单"), "清单：" + items.joinToString("、")),
            route = call.ref?.takeIf { call.ok }?.let { ReminderRoutes.edit(it) }
        )
    }

    /**
     * 对话里改清单：「去超市 · 勾了 2 样 · 加了 1 样」，细目「勾了 · 鸡蛋、牛奶」。
     * 从结果那句读（「已改好「去超市」（id=3）的清单：勾了 2 样：鸡蛋、牛奶；加了 1 样：洗洁精。还剩 6 样。」）
     */
    private fun checklist(call: ToolTrace): TraceView {
        val out = call.output.orEmpty()
        val title = out.substringAfter("已改好「", "").substringBefore("」", "")
        val parts = out.substringAfter("的清单：", "").substringBefore('。').split('；')
            .map { it.trim() }.filter { it.contains(" 样：") && !it.startsWith("清单满了") }
        return TraceView(
            working = "在改清单",
            settled = if (call.failed) "没改成" else "清单",
            text = when {
                call.failed -> TraceText.reasonOf(call.output, "没改。")
                else -> (listOf(title) + parts.map { it.substringBefore('：') }).filter { it.isNotEmpty() }.joinToString(" · ")
            },
            lines = if (call.failed) emptyList() else parts.map { it.substringBefore(' ') + " · " + it.substringAfter('：') },
            route = call.ref?.takeIf { call.ok }?.let { ReminderRoutes.edit(it) }
        )
    }

    /** 删、停：「删了 · 喝水」「不再提醒 · 吃药」；几条的「删了 3 条」，细目逐条列。点不进去 */
    private fun listed(call: ToolTrace, working: String, failed: String, prefix: String, verb: String): TraceView {
        // 结果第一行：「删了 2 条：喝水；交房租」
        val head = call.output?.lineSequence()?.firstOrNull().orEmpty()
        val titles = head.substringAfter('：', "").split('；').map { it.trim() }.filter { it.isNotEmpty() }
        return TraceView(
            working = working,
            settled = if (call.failed && titles.isEmpty()) failed else "提醒",
            text = when {
                call.failed && titles.isEmpty() -> TraceText.reasonOf(call.output, prefix)
                titles.isEmpty() -> ""
                titles.size == 1 -> "$verb · ${titles.single()}"
                else -> "$verb ${titles.size} 条"
            },
            lines = if (titles.size > 1) titles.map { "$verb · $it" } else emptyList()
        )
    }
}
