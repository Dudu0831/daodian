package com.abc.daodian.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
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
import com.abc.daodian.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工具行和回执卡片是同一个元素。见 DESIGN.md 决策 6.2 / 6.3。
 *
 * 在建提醒（工具行）→ 起稿（虚线框，标题逐字落定）→ 落印（已记下）→ 收起（一行）；
 * 校验闸门没放行时退回成「没记下」的工具行。外形全由 [CardPhase] 决定。
 */
enum class CardPhase { Running, Draft, Stamped, Collapsed, Rejected }

fun cardPhaseOf(msg: ChatMessage.AssistantTurn, draft: DraftArgs): CardPhase? = when {
    msg.plan != null -> if (msg.cardCollapsed) CardPhase.Collapsed else CardPhase.Stamped
    msg.toolName == null -> null
    msg.streaming -> if (draft.isEmpty) CardPhase.Running else CardPhase.Draft
    else -> CardPhase.Rejected
}

/** 印只在刚落下时盖一次：列表滚回来重组时，已经落定的卡直接画成终态 */
private fun isFresh(stampedAt: Long) = System.currentTimeMillis() - stampedAt < 1_500

@Composable
fun ReminderCard(
    phase: CardPhase,
    toolName: String,
    draft: DraftArgs,
    plan: ReminderPlan?,
    stampedAt: Long,
    onCollapse: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = DaodianColors.current
    val boxed = phase == CardPhase.Draft || phase == CardPhase.Stamped || phase == CardPhase.Collapsed
    val filled = phase == CardPhase.Stamped || phase == CardPhase.Collapsed

    // 工具行没有框；一起稿，框从零描出来 —— 内边距、底色、描边都是渐变过去的，不是换一张卡
    val padH by animateDpAsState(if (boxed) 18.dp else 0.dp, Motion.settle(Motion.LONG), label = "padH")
    val padTop by animateDpAsState(
        when (phase) {
            CardPhase.Draft -> 14.dp
            CardPhase.Stamped -> 16.dp
            CardPhase.Collapsed -> 13.dp
            else -> 0.dp
        },
        Motion.settle(Motion.LONG), label = "padTop"
    )
    val padBottom by animateDpAsState(
        when (phase) {
            CardPhase.Draft -> 16.dp
            CardPhase.Stamped -> 18.dp
            CardPhase.Collapsed -> 13.dp
            else -> 0.dp
        },
        Motion.settle(Motion.LONG), label = "padBottom"
    )
    val fill by animateColorAsState(
        if (filled) colors.surface else colors.surface.copy(alpha = 0f), Motion.flow(), label = "fill"
    )
    val edge by animateColorAsState(
        when (phase) {
            CardPhase.Draft -> colors.rule2
            CardPhase.Stamped -> colors.rule
            CardPhase.Collapsed -> colors.ruleSoft
            else -> colors.rule2.copy(alpha = 0f)
        },
        Motion.flow(), label = "edge"
    )
    // 草稿是虚线：还没落库，框里的字随时会变
    val dashed = phase == CardPhase.Draft

    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val r = CornerRadius(5.dp.toPx())
                drawRoundRect(fill, cornerRadius = r)
                val sw = 1.dp.toPx()
                drawRoundRect(
                    edge,
                    topLeft = Offset(sw / 2, sw / 2),
                    size = Size(size.width - sw, size.height - sw),
                    cornerRadius = r,
                    style = Stroke(
                        width = sw,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
                    )
                )
            }
            .padding(start = padH, end = padH, top = padTop, bottom = padBottom)
    ) {
        AnimatedVisibility(
            visible = phase != CardPhase.Collapsed,
            enter = expandVertically(Motion.flow()) + fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
        ) {
            CardHead(phase, toolName, stampedAt)
        }
        AnimatedVisibility(
            visible = phase == CardPhase.Running || phase == CardPhase.Draft,
            enter = fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
        ) {
            InkHairline(Modifier.padding(top = 10.dp))
        }
        AnimatedVisibility(
            visible = boxed,
            enter = expandVertically(Motion.settle(Motion.LONG)) + fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
        ) {
            CardBody(phase, draft, plan, stampedAt, onCollapse, onEdit)
        }
    }
}

/** 头：圆点「在建提醒」 / 朱砂印「已记下」 / ×「没记下」，后面跟工具名 */
@Composable
private fun CardHead(phase: CardPhase, toolName: String, stampedAt: Long) {
    val colors = DaodianColors.current
    val label = when (phase) {
        CardPhase.Stamped, CardPhase.Collapsed -> "已记下"
        CardPhase.Rejected -> "没记下"
        else -> "在建提醒"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                CardPhase.Stamped, CardPhase.Collapsed -> StampSeal(stampedAt)
                CardPhase.Rejected -> Text("×", color = colors.muted, fontSize = 14.sp, lineHeight = 14.sp)
                else -> Box(Modifier.size(5.dp).background(colors.accent, CircleShape))
            }
        }
        Crossfade(targetState = label, animationSpec = tween(Motion.SHORT), label = "headLabel") { l ->
            when (l) {
                "已记下" -> Text(l, style = DaodianType.stampLabel, color = colors.accent)
                "没记下" -> Text(l, style = DaodianType.caption, color = colors.muted)
                else -> Text(l, style = DaodianType.caption, color = colors.ink2)
            }
        }
        // 落印之后工具名退场：卡上的「已记下」和它说的是同一件事
        AnimatedVisibility(
            visible = phase != CardPhase.Stamped && phase != CardPhase.Collapsed,
            enter = fadeIn(Motion.flow()) + expandHorizontally(Motion.flow()),
            exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.flow())
        ) {
            Text(toolName, style = DaodianType.toolName.copy(fontSize = 11.sp), color = colors.muted, maxLines = 1)
        }
    }
}

/**
 * 整个回合唯一的重拍：朱砂印从 1.45 倍、−10° 盖下来，带一点回弹，印边洇开一圈。
 * 不震动 —— 聊天里震一下太突兀（2026-09-11 用户反馈），震动只留给到点响铃。
 */
@Composable
private fun StampSeal(stampedAt: Long) {
    val colors = DaodianColors.current
    val fresh = remember { isFresh(stampedAt) }
    val scale = remember { Animatable(if (fresh) 1.45f else 1f) }
    val rotation = remember { Animatable(if (fresh) -10f else 0f) }
    val alpha = remember { Animatable(if (fresh) 0f else 1f) }
    val bleed = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (!fresh) return@LaunchedEffect
        bleed.snapTo(0f)
        launch { scale.animateTo(1f, Motion.stamp()) }
        launch { rotation.animateTo(0f, Motion.stamp()) }
        launch { alpha.animateTo(1f, tween(Motion.SHORT)) }
        launch {
            delay(200)
            bleed.animateTo(1f, tween(640, easing = LinearOutSlowInEasing))
        }
    }

    Box(
        Modifier
            .size(16.dp)
            .drawBehind {
                val b = bleed.value
                if (b < 1f) {
                    val grow = 7.dp.toPx() * b
                    drawRoundRect(
                        colors.accent.copy(alpha = 0.4f * (1f - b)),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(3.dp.toPx() + grow),
                        style = Stroke(1.dp.toPx())
                    )
                }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotation.value
                this.alpha = alpha.value
            }
            .border(1.dp, colors.accent, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        CheckIcon(size = 10.dp, tint = colors.accent)
    }
}

@Composable
private fun CardBody(
    phase: CardPhase,
    draft: DraftArgs,
    plan: ReminderPlan?,
    stampedAt: Long,
    onCollapse: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = DaodianColors.current
    val collapsed = phase == CardPhase.Collapsed
    val triggerMillis = remember(plan) { plan?.let { runCatching { PlanValidator.triggerMillis(it) }.getOrNull() } }
    val rruleText = remember(plan?.rrule) { Format.humanRrule(plan?.rrule) }
    val title = plan?.title ?: draft.title
    // 重复的报「每天 08:00」，一次性的报完整日期 —— 重复的那条写全日期没意义
    val whenText = when {
        triggerMillis == null -> draft.whenText
        rruleText != null -> "$rruleText ${Format.clock(triggerMillis)}"
        else -> Format.humanDateTime(triggerMillis)
    }
    val shortWhen = when {
        triggerMillis == null -> ""
        rruleText != null -> "$rruleText ${Format.clock(triggerMillis)}"
        else -> Format.humanDateTimeShort(triggerMillis)
    }

    // 收起是同一张卡变形：标题 22 → 16，时间滑到右边，左边补一个小对勾
    val titleSize by animateFloatAsState(if (collapsed) 16f else 22f, Motion.flow(), label = "titleSize")
    val titleTop by animateDpAsState(if (collapsed) 0.dp else 10.dp, Motion.flow(), label = "titleTop")

    Column {
        Row(Modifier.padding(top = titleTop), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = collapsed,
                enter = expandHorizontally(Motion.flow()) + fadeIn(Motion.flow()),
                exit = shrinkHorizontally(Motion.flow()) + fadeOut(Motion.exit())
            ) {
                CheckIcon(Modifier.padding(end = 12.dp), size = 12.dp, tint = colors.accent)
            }
            Box(Modifier.weight(1f).heightIn(min = 22.dp), contentAlignment = Alignment.CenterStart) {
                if (title.isEmpty()) {
                    DraftSlot(88.dp, 12.dp)
                } else {
                    InkText(
                        title,
                        streaming = phase == CardPhase.Draft,
                        style = DaodianType.cardTitle.copy(fontSize = titleSize.sp, lineHeight = (titleSize * 30f / 22f).sp),
                        color = if (phase == CardPhase.Draft) colors.ink2 else colors.ink
                    )
                }
            }
            AnimatedVisibility(
                visible = collapsed,
                enter = expandHorizontally(Motion.flow()) + fadeIn(Motion.flow()),
                exit = shrinkHorizontally(Motion.flow()) + fadeOut(Motion.exit())
            ) {
                Text(
                    shortWhen, style = DaodianType.caption, color = colors.muted, maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(Motion.flow()) + fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
        ) {
            // 时间那串 ISO 收全了才换成人话，之前先留占位条
            Crossfade(
                targetState = whenText, animationSpec = tween(Motion.SHORT), label = "when",
                modifier = Modifier.padding(top = 4.dp)
            ) { w ->
                if (w == null) DraftSlot(120.dp, 11.dp)
                else Text(w, fontSize = 14.sp, lineHeight = 20.sp, color = colors.ink2)
            }
        }

        AnimatedVisibility(
            visible = phase == CardPhase.Stamped && plan != null,
            enter = expandVertically(Motion.settle(Motion.MID, delay = 80)),
            exit = shrinkVertically(Motion.flow(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
        ) {
            if (plan != null) CardDetails(plan, triggerMillis, rruleText, isFresh(stampedAt), onCollapse, onEdit)
        }
    }
}

/** 落印之后才展开的几行：「下一次」、徽标、依据、两个按钮，错开 60ms 依次浮上来 */
@Composable
private fun CardDetails(
    plan: ReminderPlan,
    triggerMillis: Long?,
    rruleText: String?,
    fresh: Boolean,
    onCollapse: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = DaodianColors.current
    val nowMillis = remember { System.currentTimeMillis() }
    Column {
        if (triggerMillis != null) {
            Stagger(0, fresh) {
                Text(
                    if (rruleText != null) "下一次 · ${Format.relative(triggerMillis, nowMillis)}"
                    else Format.relative(triggerMillis, nowMillis),
                    style = DaodianType.caption, color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (rruleText != null) {
            Stagger(1, fresh) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepeatBadge(rruleText)
                    // 时区锚定是「每天早上 8 点吃药」和「9月2号15:00的会」的分水岭，必须能看见
                    OutlineBadge(if (plan.wallClockAnchored) "跟着所在时区" else "固定这一瞬间")
                }
            }
        }
        // 「依据」是模型的推算过程 —— 算错时唯一能看出哪儿歪了的线索，不要删（见视觉稿组件展板批注）
        if (plan.basis.isNotBlank()) {
            Stagger(2, fresh) {
                Column(Modifier.padding(top = 15.dp)) {
                    HorizontalDivider(color = colors.ruleSoft)
                    Spacer(Modifier.height(11.dp))
                    Text("依据 · ${plan.basis}", style = DaodianType.basis, color = colors.muted)
                }
            }
        }
        // 已经落库排好了 —— 卡片是回执不是确认框：「就这样」只是收起，「改一下」进编辑页
        Stagger(3, fresh) {
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                PillButton("就这样", PillStyle.Solid, onCollapse)
                PillButton("改一下", PillStyle.Outline, onEdit)
            }
        }
    }
}

@Composable
private fun Stagger(index: Int, fresh: Boolean, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (fresh) p.animateTo(1f, Motion.flow(Motion.MID, delay = 120 + index * 60))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * 6.dp.toPx()
        }
    ) { content() }
}

/** 字还没到的那一行：先留一根占位条，高度不跳 */
@Composable
private fun DraftSlot(width: Dp, height: Dp) {
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .size(width, height)
            .background(DaodianColors.current.skeleton, RoundedCornerShape(2.dp))
    )
}

internal enum class PillStyle { Solid, Outline, OutlineStrong }

@Composable
internal fun PillButton(text: String, style: PillStyle, onClick: () -> Unit) {
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
