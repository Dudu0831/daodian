package com.abc.daodian.ledger.tools

import com.abc.daodian.agent.feature.PartialArgs
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceText
import com.abc.daodian.agent.feature.TraceView
import com.abc.daodian.ledger.LedgerRoutes
import com.abc.daodian.ledger.domain.Money
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** 记账的写操作在对话里留的痕：记了一笔、改了几笔、加了类别。点了去那一笔。见 DESIGN.md §6.6 */
object LedgerTrace {

    private val mapper = ObjectMapper()
    private fun JsonNode.s(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    fun of(call: ToolTrace): TraceView? {
        val args = runCatching { mapper.readTree(call.arguments) }.getOrNull()
        return when (call.tool) {
            AddExpenseTool.NAME -> TraceView(
                working = "在记账",
                settled = if (call.failed) "没记成" else "记账",
                text = when {
                    call.failed -> TraceText.reasonOf(call.output, "没记。")
                    args != null -> listOfNotNull(
                        args.s("summary"),
                        Money.parseCents(args.s("amount"))?.let { "¥" + Money.yuan(it) },
                    ).joinToString(" ") + if (call.ok) " · " + (args.s("category") ?: "未归类") else ""
                    else -> PartialArgs.text(call.arguments, "summary").orEmpty()
                },
                route = call.ref?.takeIf { call.ok }?.let(LedgerRoutes::txn)
            )
            UpdateExpensesTool.NAME -> {
                // 结果第一行：「改好了 3 笔：午饭 36.50 → 餐饮/堂食；…」
                val head = call.output?.lineSequence()?.firstOrNull().orEmpty()
                val lines = head.substringAfter('：', "").split('；').map { it.trim() }.filter { it.isNotEmpty() }
                TraceView(
                    working = "在改账",
                    settled = if (call.failed) "没改成" else "记账",
                    text = when {
                        call.failed -> head.ifEmpty { "没改成" }
                        !call.ok -> ""
                        lines.size == 1 -> lines.single()
                        else -> "改了 ${lines.size} 笔"
                    },
                    lines = if (call.ok && lines.size > 1) lines else emptyList(),
                    route = call.ref?.takeIf { call.ok && lines.size == 1 }?.let(LedgerRoutes::txn)
                )
            }
            AddCategoryTool.NAME -> TraceView(
                working = "在加类别",
                settled = if (call.failed) "没加成" else "类别",
                text = when {
                    call.failed -> TraceText.reasonOf(call.output, "没建。")
                    call.ok -> call.output?.lineSequence()?.firstOrNull()?.removePrefix("建好了：").orEmpty()
                    else -> listOfNotNull(args?.s("parent"), args?.s("name")).joinToString("/")
                }
            )
            else -> null
        }
    }
}
