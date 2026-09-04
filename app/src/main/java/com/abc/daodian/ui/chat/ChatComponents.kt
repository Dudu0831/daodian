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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.ai.PlanValidator
import com.abc.daodian.ai.ReminderPlan
import com.abc.daodian.ui.common.CheckIcon
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.common.OutlineBadge
import com.abc.daodian.ui.common.RepeatBadge
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/** 助手说的话一律缩进这么多，跟上面的「· 到点」标签对齐 */
private val AssistantIndent = 14.dp

@Composable
fun UserBubble(text: String) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            style = DaodianType.body,
            color = colors.onSolid,
            modifier = Modifier
                .widthIn(max = 264.dp)
                .background(colors.solid, RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

/** 朱砂小圆点 + 「到点」。出错时圆点褪成灰的 —— 报错不该比正常回答更抢眼 */
@Composable
fun SpeakerTag(isError: Boolean = false) {
    val colors = DaodianColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(5.dp).background(if (isError) colors.rule2 else colors.accent, CircleShape))
        Text("到点", style = DaodianType.speakerTag, color = colors.muted)
    }
}

@Composable
fun AssistantTextRow(
    text: String,
    isError: Boolean,
    onManualAdd: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    val colors = DaodianColors.current
    Column {
        SpeakerTag(isError = isError)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(start = AssistantIndent)) {
            Text(text, style = DaodianType.prose, color = colors.ink)
            if (isError) {
                Text(
                    "你可以自己填一条，跟解析出来的一样能用。",
                    style = DaodianType.prose,
                    color = colors.muted
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    onManualAdd?.let { PillButton("手动填一条", PillStyle.OutlineStrong, it) }
                    onRetry?.let { PillButton("重试", PillStyle.Outline, it) }
                }
            }
        }
    }
}

/**
 * 解析等待的占位。实测 6–7 秒，用「正在写字」的骨架条撑着，不用转圈 ——
 * 转圈是「系统在忙」，骨架条是「答案正在成形」，后者才是这里的真相。见 DESIGN.md §8.1
 */
@Composable
fun ThinkingRow() {
    val colors = DaodianColors.current
    val transition = rememberInfiniteTransition(label = "thinking")
    Column {
        SpeakerTag()
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.padding(start = AssistantIndent),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            listOf(0.78f, 0.54f, 0.31f).forEachIndexed { i, fraction ->
                val alpha by transition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, delayMillis = i * 180, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bar$i"
                )
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(11.dp)
                        .background(colors.skeleton.copy(alpha = alpha), RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "正在推算时间——",
                style = DaodianType.thinkingNote,
                color = colors.hint
            )
        }
    }
}

private enum class PillStyle { Solid, Outline, OutlineStrong }

@Composable
private fun PillButton(text: String, style: PillStyle, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .let { if (style == PillStyle.Solid) it.background(colors.solid, shape) else it }
            .let {
                when (style) {
                    PillStyle.Solid -> it
                    PillStyle.Outline -> it.border(1.dp, colors.rule2, shape)
                    PillStyle.OutlineStrong -> it.border(1.dp, colors.ink, shape)
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = DaodianType.bodySmall,
            color = when (style) {
                PillStyle.Solid -> colors.onSolid
                PillStyle.OutlineStrong -> colors.ink
                PillStyle.Outline -> colors.ink2
            }
        )
    }
}

/**
 * 已建提醒的回执卡片。工具调用成功时已经落库了 —— 这不是「请确认」表单。
 * 「就这样」= 收起；「改一下」= 跳编辑页微调。见 DESIGN.md §08 界面
 */
@Composable
fun ReminderCardExpanded(
    plan: ReminderPlan,
    nowMillis: Long,
    onCollapse: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(5.dp)
    val triggerMillis = remember(plan) { runCatching { PlanValidator.triggerMillis(plan) }.getOrNull() }
    val whenText = triggerMillis?.let { Format.humanDateTime(it) } ?: Format.humanDateTime(plan.firstTriggerAt)
    val rruleText = remember(plan.rrule) { Format.humanRrule(plan.rrule) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = AssistantIndent)
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            CheckIcon(tint = colors.accent)
            Text("已记下", style = DaodianType.stampLabel, color = colors.accent)
        }

        Spacer(Modifier.height(13.dp))
        Text(plan.title, style = DaodianType.cardTitle, color = colors.ink)
        Spacer(Modifier.height(6.dp))

        // 重复的提醒报「每天 08:00」，一次性的报完整日期 —— 重复的那条写全日期没意义
        Text(
            if (rruleText != null && triggerMillis != null) "$rruleText ${Format.clock(triggerMillis)}" else whenText,
            fontSize = 14.sp,
            color = colors.ink2
        )
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                triggerMillis == null -> ""
                rruleText != null -> "下一次 · ${Format.relative(triggerMillis, nowMillis)}"
                else -> Format.relative(triggerMillis, nowMillis)
            },
            style = DaodianType.caption, color = colors.muted
        )

        if (rruleText != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatBadge(rruleText)
                // 时区锚定是「每天早上 8 点吃药」和「9月2号15:00的会」的分水岭，必须能看见
                OutlineBadge(if (plan.wallClockAnchored) "跟着所在时区" else "固定这一瞬间")
            }
        }

        // 「依据」是模型的推算过程 —— 算错时唯一能看出哪儿歪了的线索，不要删（见视觉稿组件展板批注）
        if (plan.basis.isNotBlank()) {
            Spacer(Modifier.height(15.dp))
            HorizontalDivider(color = colors.ruleSoft)
            Spacer(Modifier.height(11.dp))
            Text("依据 · ${plan.basis}", style = DaodianType.basis, color = colors.muted)
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            PillButton("就这样", PillStyle.Solid, onCollapse)
            PillButton("改一下", PillStyle.Outline, onEdit)
        }
    }
}

@Composable
fun ReminderCardCollapsed(plan: ReminderPlan) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(5.dp)
    val triggerMillis = remember(plan) { runCatching { PlanValidator.triggerMillis(plan) }.getOrNull() }
    val whenText = triggerMillis?.let { Format.humanDateTimeShort(it) } ?: plan.firstTriggerAt

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = AssistantIndent)
            .background(colors.surface, shape)
            .border(1.dp, colors.ruleSoft, shape)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CheckIcon(size = 12.dp, tint = colors.accent)
        Text(plan.title, style = DaodianType.rowTitle, color = colors.ink, modifier = Modifier.weight(1f))
        Text(whenText, style = DaodianType.caption, color = colors.muted)
    }
}
