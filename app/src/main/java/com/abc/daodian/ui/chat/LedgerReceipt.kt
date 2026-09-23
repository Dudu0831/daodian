package com.abc.daodian.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abc.daodian.harness.builtin.ledger.AddCategoryTool
import com.abc.daodian.harness.builtin.ledger.AddExpenseTool
import com.abc.daodian.harness.builtin.ledger.Direction
import com.abc.daodian.harness.builtin.ledger.ListExpensesTool
import com.abc.daodian.harness.builtin.ledger.Money
import com.abc.daodian.harness.builtin.ledger.RecordExpensesTool
import com.abc.daodian.harness.builtin.ledger.UpdateExpensesTool
import com.abc.daodian.ui.common.CheckIcon
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

private val mapper = ObjectMapper()

private fun JsonNode.s(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

/** 记账工具的一次调用翻成人话：授权条和对话里的回执共用。认不出就老实摆参数 */
fun ledgerSummaryOf(tool: String, arguments: String): ApprovalSummary {
    val o = runCatching { mapper.readTree(arguments) }.getOrNull()
    return when (tool) {
        AddExpenseTool.NAME -> {
            val direction = Direction.entries.firstOrNull { it.name == o?.s("direction") } ?: Direction.OUT
            val cents = Money.parseCents(o?.s("amount"))
            val at = o?.s("occurred_at")?.let { runCatching { Format.humanDateTime(OffsetDateTime.parse(it).toInstant().toEpochMilli()) }.getOrNull() }
            ApprovalSummary(
                label = "要记一笔账",
                title = listOfNotNull(o?.s("summary"), cents?.let { "${direction.label} ¥${Money.yuan(it)}" }).joinToString(" · "),
                detail = listOfNotNull(o?.s("category") ?: "未归类", at).joinToString(" · ")
            )
        }
        UpdateExpensesTool.NAME -> {
            val changes = o?.get("changes")?.takeIf { it.isArray }?.toList().orEmpty()
            val lines = changes.map { c ->
                val id = c.get("txn_id")?.asLong()
                when {
                    c.s("void_reason") != null -> "作废 #$id（${c.s("void_reason")}）"
                    c.get("split")?.isArray == true && c.get("split").size() > 0 ->
                        "#$id 拆成 " + c.get("split").joinToString(" + ") { "${it.s("category")} ${it.s("amount")}" }
                    c.s("category") != null -> "#$id 归到 ${c.s("category")}" + if (c.get("remember_merchant")?.asBoolean() == true) "，以后这家都这么归" else ""
                    else -> "#$id " + listOfNotNull(c.s("summary"), c.s("note"), c.s("merchant")).joinToString(" · ").ifEmpty { "确认" }
                }
            }
            ApprovalSummary(
                label = if (changes.size > 1) "要改 ${changes.size} 笔账" else "要改一笔账",
                title = lines.firstOrNull().orEmpty(),
                detail = lines.drop(1).joinToString("；")
            )
        }
        AddCategoryTool.NAME -> ApprovalSummary(
            label = "要加一个类别",
            title = listOfNotNull(o?.s("parent"), o?.s("name")).joinToString("/"),
            detail = if (o?.s("parent") == null) "一级类别，列表和统计里会多一行" else "二级类别"
        )
        ListExpensesTool.NAME -> ApprovalSummary(
            label = "查账",
            title = listOfNotNull(
                listOfNotNull(o?.s("from"), o?.s("to")).joinToString(" ~ ").ifEmpty { null },
                o?.s("category"), o?.s("keyword"), o?.s("tag")
            ).joinToString(" · ").ifEmpty { "最近的账" },
            detail = ""
        )
        RecordExpensesTool.NAME -> ApprovalSummary("整理账目", "", "")
        else -> ApprovalSummary("要执行 $tool", "", arguments.take(80))
    }
}

/**
 * 回合里一次记账操作的回执，一行到两行，放在提醒卡片下面、正文上面。
 *
 * 查账是安静的一行灰字（「查了账 · 共 3 笔…」）；写操作办成了是一张小纸条 + 朱砂小勾，
 * 等你点头时是虚线框，没办成划掉。点头本身在输入框上方的授权条上，这里只说「它要做什么」。
 */
@Composable
fun LedgerOpRow(op: LedgerOp) {
    val colors = DaodianColors.current
    val summary = ledgerSummaryOf(op.tool, op.arguments)

    if (op.tool == ListExpensesTool.NAME) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(4.dp).background(if (op.ok == null) colors.hint else colors.rule2, CircleShape))
            Text(
                "查账 · " + (op.result ?: summary.title),
                style = DaodianType.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    val shape = RoundedCornerShape(5.dp)
    val done = op.ok == true
    val failed = op.ok == false
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                when {
                    done -> Modifier.background(colors.surface, shape).border(1.dp, colors.rule, shape)
                    failed -> Modifier
                    else -> Modifier.border(1.dp, colors.rule2, shape)
                }
            )
            .padding(horizontal = if (failed) 0.dp else 14.dp, vertical = if (failed) 0.dp else 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    done -> "已记下"
                    failed -> "× 没记"
                    op.awaiting -> "等你确认"
                    else -> "在记账"
                } + " · " + summary.label.removePrefix("要"),
                style = DaodianType.toolName, color = if (done) colors.accent else colors.muted,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (done) CheckIcon(tint = colors.accent)
        }
        val main = if (done) op.result?.substringAfter('：', op.result)?.ifBlank { null } ?: summary.title else summary.title
        if (main.isNotBlank()) {
            Text(
                main,
                style = DaodianType.bodySmall,
                color = if (failed) colors.hint else colors.ink,
                textDecoration = if (failed) TextDecoration.LineThrough else null,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
        }
        if (!done && summary.detail.isNotBlank()) {
            Text(summary.detail, style = DaodianType.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** app 自己发起的一轮（每晚对账）：不是用户说的话，画成一条分隔线 */
@Composable
fun TriggerDivider(msg: ChatMessage.UserText) {
    val colors = DaodianColors.current
    val label = when {
        msg.text.startsWith("每晚对账") -> "每晚对账"
        else -> msg.text.substringBefore('：').take(12)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.rule)
        Text(
            (if (msg.sentAt > 0) Format.clock(msg.sentAt) + " · " else "") + label,
            style = DaodianType.speakerTag, color = colors.hint
        )
        HorizontalDivider(Modifier.weight(1f), color = colors.rule)
    }
}
