package com.abc.daodian.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.ai.PlanValidator
import com.abc.daodian.ai.ReminderPlan
import com.abc.daodian.ui.common.CheckIcon
import com.abc.daodian.ui.common.ChevronRightIcon
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

@Composable
fun UserBubble(text: String) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            style = DaodianType.body,
            color = colors.onGreen,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(colors.green, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun AssistantLabel(isError: Boolean = false) {
    val colors = DaodianColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).background(if (isError) colors.red else colors.green, CircleShape))
        Text("到点", style = DaodianType.monoLabel, color = colors.muted)
    }
}

@Composable
fun AssistantTextRow(text: String, isError: Boolean, onManualAdd: (() -> Unit)? = null) {
    val colors = DaodianColors.current
    Column {
        AssistantLabel(isError = isError)
        Spacer(Modifier.height(10.dp))
        Text(text, style = DaodianType.body, color = colors.ink2)
        if (isError && onManualAdd != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .border(1.dp, colors.rule2, RoundedCornerShape(100.dp))
                    .clickable(onClick = onManualAdd)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("手动添加这条提醒", style = DaodianType.bodySmall, color = colors.ink2)
                ChevronRightIcon(size = 13.dp, tint = colors.muted)
            }
        }
    }
}

/** 呼吸感占位，撑住实测 6–7 秒的解析等待。不用转圈，见 DESIGN.md §02 */
@Composable
fun ThinkingRow() {
    val colors = DaodianColors.current
    Column {
        AssistantLabel(isError = false)
        Spacer(Modifier.height(12.dp))
        val transition = rememberInfiniteTransition(label = "thinking")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.22f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, delayMillis = i * 160, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dot$i"
                )
                Box(Modifier.size(7.dp).background(colors.green.copy(alpha = alpha), CircleShape))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("正在想……", style = DaodianType.caption, color = colors.muted)
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val colors = DaodianColors.current
    Box(
        Modifier
            .let { if (filled) it.background(colors.green, RoundedCornerShape(100.dp)) else it }
            .let { if (!filled) it.border(1.dp, colors.rule2, RoundedCornerShape(100.dp)) else it }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(text, style = DaodianType.bodySmall, color = if (filled) colors.onGreen else colors.ink2)
    }
}

/**
 * 已建提醒的回执卡片。工具调用成功时已经落库了 —— 这不是「请确认」表单。
 * 「就这样」= 收起；「改一下」= 跳编辑页微调。见 DESIGN.md §03
 */
@Composable
fun ReminderCardExpanded(
    plan: ReminderPlan,
    nowMillis: Long,
    onCollapse: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = DaodianColors.current
    val triggerMillis = remember(plan) { runCatching { PlanValidator.triggerMillis(plan) }.getOrNull() }
    val whenText = triggerMillis?.let { Format.humanDateTime(it) } ?: Format.humanDateTime(plan.firstTriggerAt)
    val rruleText = remember(plan.rrule) { Format.humanRrule(plan.rrule) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(4.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(4.dp))
            .padding(20.dp)
    ) {
        Text("已建提醒", style = DaodianType.monoLabel, color = colors.green)
        Spacer(Modifier.height(12.dp))
        Text(plan.title, style = DaodianType.cardTitle, color = colors.ink)
        Spacer(Modifier.height(5.dp))

        if (rruleText != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(whenText, fontSize = 14.sp, color = colors.ink2)
                Box(
                    Modifier
                        .background(colors.greenSoft, RoundedCornerShape(100.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(rruleText, style = DaodianType.monoSmall, color = colors.greenInk)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (plan.wallClockAnchored) "跟着所在时区走" else "固定这一瞬间",
                style = DaodianType.caption, color = colors.muted
            )
        } else {
            Text(whenText, fontSize = 14.sp, color = colors.ink2)
            Spacer(Modifier.height(3.dp))
            Text(
                triggerMillis?.let { Format.relative(it, nowMillis) } ?: "",
                style = DaodianType.caption, color = colors.muted
            )
        }

        Spacer(Modifier.height(14.dp))
        if (plan.basis.isNotBlank()) {
            androidx.compose.material3.HorizontalDivider(color = colors.rule)
            Spacer(Modifier.height(12.dp))
            Text(
                "依据：${plan.basis}",
                fontFamily = DaodianType.monoSmall.fontFamily,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.muted
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("就这样", filled = true, onClick = onCollapse)
            PillButton("改一下", filled = false, onClick = onEdit)
        }
    }
}

@Composable
fun ReminderCardCollapsed(plan: ReminderPlan) {
    val colors = DaodianColors.current
    val triggerMillis = remember(plan) { runCatching { PlanValidator.triggerMillis(plan) }.getOrNull() }
    val whenText = triggerMillis?.let { Format.humanDateTime(it) } ?: plan.firstTriggerAt

    Row(
        Modifier
            .background(colors.surface, RoundedCornerShape(100.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(100.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CheckIcon(tint = colors.green)
        Text("${plan.title} · $whenText", style = DaodianType.bodySmall, color = colors.ink2)
    }
}
