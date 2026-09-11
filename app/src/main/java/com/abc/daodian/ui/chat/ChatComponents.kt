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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

/**
 * 请求发出去了，一个字都还没回来。用「正在写字」的骨架条撑着，不用转圈 ——
 * 转圈是「系统在忙」，骨架条是「答案正在成形」，后者才是这里的真相。见 DESIGN.md §8.1
 *
 * 只有三根条，没有说明文字：首字延迟本来就短不了，再配一句解说反而像在道歉。
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
        }
    }
}

/**
 * 助手的一个回合。见 DESIGN.md §6.7 和设计稿。
 *
 * 四块按这个顺序摞：思考 → 工具行 → 正文 → 卡片。工具行在正文**上面** ——
 * 模型的动作先于它的解说，也让「这一回合动了手」不用读完整段话才知道。
 */
@Composable
fun AssistantTurnRow(
    msg: ChatMessage.AssistantTurn,
    onToggleReasoning: () -> Unit,
    onCollapseCard: () -> Unit,
    onEditReminder: () -> Unit,
    onManualAdd: () -> Unit,
    onRetry: () -> Unit
) {
    if (msg.isBlank) {
        ThinkingRow()
        return
    }
    val colors = DaodianColors.current
    Column {
        SpeakerTag(isError = msg.isError)
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.padding(start = AssistantIndent),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (msg.reasoning.isNotBlank()) {
                if (msg.streaming) {
                    ReasoningStream(msg.reasoning)
                } else {
                    ReasoningTrace(msg.reasoning, msg.thoughtSeconds, msg.reasoningOpen, onToggleReasoning)
                }
            }

            if (msg.showToolRow) ToolRow(msg.toolName!!, running = msg.toolRunning)

            if (msg.text.isNotBlank()) {
                Text(
                    if (msg.streaming) withCaret(msg.text, colors.ink) else buildAnnotatedString { append(msg.text) },
                    style = DaodianType.prose,
                    color = colors.ink
                )
            }

            msg.plan?.let { plan ->
                if (msg.cardCollapsed) {
                    ReminderCardCollapsed(plan)
                } else {
                    ReminderCardExpanded(
                        plan = plan,
                        nowMillis = System.currentTimeMillis(),
                        onCollapse = onCollapseCard,
                        onEdit = onEditReminder
                    )
                }
            }

            if (msg.isError) {
                Text(
                    "你可以自己填一条，跟解析出来的一样能用。",
                    style = DaodianType.prose,
                    color = colors.muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    PillButton("手动填一条", PillStyle.OutlineStrong, onManualAdd)
                    PillButton("重试", PillStyle.Outline, onRetry)
                }
            }
        }
    }
}

/**
 * 工具行。**只露工具名，参数不上屏** —— 参数是给日志看的，不是给人看的。
 *
 * 跑着的时候整行呼吸式明暗，它是这几秒里屏幕上唯一在动的东西；
 * 落定后换成朱砂对勾、字压到 muted。什么时候该藏见 [ChatMessage.AssistantTurn.showToolRow]。
 */
@Composable
private fun ToolRow(name: String, running: Boolean) {
    val colors = DaodianColors.current
    val alpha by rememberInfiniteTransition(label = "tool").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1250, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "toolAlpha"
    )
    Row(
        Modifier.alpha(if (running) alpha else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (running) {
            Box(Modifier.size(5.dp).background(colors.accent, CircleShape))
        } else {
            CheckIcon(tint = colors.accent)
        }
        Text(name, style = DaodianType.toolName, color = if (running) colors.ink2 else colors.muted)
    }
}

/** 思考过程流着的时候：左边一条细线圈出来，字压到最轻的一档 */
@Composable
private fun ReasoningStream(text: String) {
    val colors = DaodianColors.current
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))
        Text(withCaret(text, colors.muted), style = DaodianType.thinkingNote, color = colors.muted)
    }
}

/**
 * 流完之后折成一行「想了 3 秒 · 看看」。不直接删：
 * 模型把时间算歪的时候，这段和卡片上的「依据」是仅有的两条线索。
 */
@Composable
private fun ReasoningTrace(text: String, seconds: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = DaodianColors.current
    Column {
        Row(
            Modifier.clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                if (seconds > 0) "想了 $seconds 秒" else "想了一下",
                style = DaodianType.speakerTag,
                color = colors.hint
            )
            Text(if (expanded) "收起" else "看看", style = DaodianType.speakerTag, color = colors.accent)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))
                Text(text, style = DaodianType.thinkingNote, color = colors.muted)
            }
        }
    }
}

/** 末字后面跟一个墨块光标。inline 拼进同一个 Text，换行时自己跟着走，不用额外布局 */
@Composable
private fun withCaret(text: String, tint: Color): AnnotatedString {
    val alpha by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "caretAlpha"
    )
    return buildAnnotatedString {
        append(text)
        withStyle(SpanStyle(color = tint.copy(alpha = alpha))) { append("\u258d") }
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
