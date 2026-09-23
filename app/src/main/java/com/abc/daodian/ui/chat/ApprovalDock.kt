package com.abc.daodian.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.harness.Item
import com.abc.daodian.harness.builtin.ledger.LedgerTools
import com.abc.daodian.harness.builtin.reminder.CreateReminderTool
import com.abc.daodian.harness.builtin.reminder.PlanValidator
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import com.abc.daodian.ui.theme.Motion

/** 授权条上写的三样：要干什么、办的是什么、细节。都是人话 */
data class ApprovalSummary(val label: String, val title: String, val detail: String)

/** 按工具把一次调用翻成人话。认不出的工具就老实摆出工具名和参数 —— 宁可糙，不可猜 */
fun approvalSummaryOf(call: Item.ToolCall): ApprovalSummary = when (call.name) {
    CreateReminderTool.NAME -> CreateReminderTool.planOf(call.arguments)?.let { plan ->
        val whenText = if (plan.allDay) {
            runCatching { PlanValidator.dueDayOf(plan) }.getOrNull()?.let { "${Format.humanDay(it)} · 当天之内" }
        } else {
            runCatching { Format.humanDateTime(PlanValidator.triggerMillis(plan)) }.getOrNull()
        }
        ApprovalSummary(
            label = "要建一条提醒",
            title = plan.title,
            detail = listOfNotNull(whenText, Format.humanRrule(plan.rrule) ?: "不重复").joinToString(" · ")
        )
    } ?: ApprovalSummary("要建一条提醒", "", call.arguments.take(80))
    in LedgerTools.NAMES -> ledgerSummaryOf(call.name, call.arguments)
    else -> ApprovalSummary("要执行 ${call.name}", "", call.arguments.take(80))
}

/**
 * 授权条：写操作要你点头时，钉在输入框上方。设计稿：
 * <https://claude.ai/artifact/5Lu9cTegZ6wC5LYViWhtmJ>，规则见 DESIGN.md §6.8。
 *
 * 聊天里的卡片只说「它要做什么」，点头在这里 —— 卡片滚出屏幕了，授权条还在手边。
 * [typing]：你在输入框里打字了 —— 发出去就是「不」+ 你的话，所以这里缩成一行、按钮变灰，
 * 把地方让给键盘。[canRedirect] 为 false 的地方（桌面速记没有输入框）不提打字这条路。
 */
@Composable
fun ApprovalDock(
    summary: ApprovalSummary,
    typing: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    canRedirect: Boolean = true
) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .animateContentSize(Motion.flow())
            .padding(start = 16.dp, end = 16.dp, top = if (typing) 12.dp else 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (typing) 10.dp else 12.dp)
    ) {
        if (typing && canRedirect) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(summary.label, style = DaodianType.caption.copy(fontSize = 12.sp), color = colors.muted)
                    Text(
                        listOf(summary.title, summary.detail).filter { it.isNotBlank() }.joinToString(" · "),
                        style = DaodianType.caption.copy(fontSize = 13.sp), color = colors.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DockButton("好", solid = false, enabled = false, width = 56.dp, height = 36.dp, onClick = onApprove)
                    DockButton("不", solid = false, enabled = false, width = 56.dp, height = 36.dp, onClick = onDeny)
                }
            }
            HorizontalDivider(color = colors.ruleSoft)
            Text("发出去就是「不」，这句话交给它重新办", style = DaodianType.caption, color = colors.ink2)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary.label, style = DaodianType.caption.copy(fontSize = 12.sp), color = colors.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (summary.title.isNotBlank()) {
                        Text(
                            summary.title,
                            style = DaodianType.rowTitle.copy(fontSize = 18.sp), color = colors.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        )
                    }
                    // 细节不折行：挤不下时让标题先省略（真机上「不重复」被挤成过两行）
                    Text(
                        summary.detail, style = DaodianType.caption.copy(fontSize = 13.sp), color = colors.ink2,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DockButton("好", solid = true, enabled = true, modifier = Modifier.weight(1f), onClick = onApprove)
                DockButton("不", solid = false, enabled = true, width = 96.dp, onClick = onDeny)
            }
            if (canRedirect) {
                Text(
                    "也可以直接在下面说要怎么改",
                    style = DaodianType.caption.copy(fontSize = 12.sp), color = colors.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DockButton(
    text: String,
    solid: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 44.dp
) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(height / 2)
    val (fill, edge, ink) = when {
        !enabled -> Triple(Color.Transparent, colors.rule, colors.muted)
        solid -> Triple(colors.solid, colors.solid, colors.onSolid)
        else -> Triple(Color.Transparent, colors.rule2, colors.ink)
    }
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(height)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = DaodianType.body.copy(fontSize = 15.sp), color = ink)
    }
}
