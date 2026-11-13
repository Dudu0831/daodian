package com.abc.daodian.assistant.conversation.turn

import com.abc.daodian.assistant.conversation.ask.answerLabelOf
import com.abc.daodian.assistant.conversation.render.InkText
import com.abc.daodian.assistant.conversation.ask.AskGlyph
import com.abc.daodian.assistant.conversation.ask.StampGlyph
import com.abc.daodian.assistant.conversation.AskState
import com.abc.daodian.assistant.conversation.TurnBlock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.ChevronRightIcon
import kotlinx.coroutines.launch

/**
 * 挂在「· 到点」同一行右侧的伴随印胶囊。
 *
 * 三拍动效：
 * 1. 工具在办：呼吸朱砂点 + 逐字洇出「在记提醒」；
 * 2. 问卡答完化印：正文里的卡片向上收起，同时这里的「答」印从 1.45x / −10° 盖下，胶囊微弹 + 朱砂边晕开；
 * 3. 工具办成：圆点收成一笔描出的对勾，宽度随摘要丝滑伸缩。
 */
@Composable
internal fun TurnImprintPill(
    sealedAsks: List<TurnBlock.Ask>,
    traces: List<TurnBlock.Trace>,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val colors = DaodianColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val latestAskAt = sealedAsks.maxOfOrNull { it.answeredAt } ?: 0L
    val latestTraceAt = traces.maxOfOrNull { it.doneAt } ?: 0L
    val freshAsk = isFresh(latestAskAt)
    val freshTrace = isFresh(latestTraceAt)
    val anyFailed = traces.any { it.state == TraceState.FAILED }

    // 问卡刚答完落入抬头、或工具刚办成时：胶囊轻微回弹 + 边框泛一道朱砂晕
    val impactScale = remember { Animatable(1f) }
    val borderGlow = remember { Animatable(0f) }
    LaunchedEffect(latestAskAt) {
        if (!freshAsk) return@LaunchedEffect
        launch {
            impactScale.snapTo(1.08f)
            impactScale.animateTo(1f, Motion.stamp())
        }
        launch {
            borderGlow.snapTo(1f)
            borderGlow.animateTo(0f, tween(680, easing = LinearOutSlowInEasing))
        }
    }
    LaunchedEffect(latestTraceAt) {
        if (!freshTrace) return@LaunchedEffect
        launch {
            impactScale.snapTo(1.04f)
            impactScale.animateTo(1f, Motion.settle())
        }
        launch {
            borderGlow.snapTo(0.7f)
            borderGlow.animateTo(0f, tween(520, easing = LinearOutSlowInEasing))
        }
    }

    val pressScale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.flow(Motion.SHORT), label = "imprintPress")
    val bg by animateColorAsState(
        if (expanded) colors.surfaceAlt else colors.surface,
        Motion.flow(Motion.SHORT),
        label = "imprintBg"
    )
    val baseBorder by animateColorAsState(
        when {
            anyFailed -> colors.red.copy(alpha = 0.55f)
            expanded -> colors.rule2
            else -> colors.rule
        },
        Motion.flow(Motion.SHORT),
        label = "imprintBorder"
    )
    val shape = RoundedCornerShape(12.dp)

    Row(
        Modifier
            .widthIn(max = 236.dp)
            .graphicsLayer {
                val s = impactScale.value * pressScale
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val g = borderGlow.value
                if (g > 0.01f) {
                    val grow = 4.dp.toPx() * (1f - g)
                    drawRoundRect(
                        colors.accent.copy(alpha = 0.32f * g),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(12.dp.toPx() + grow),
                        style = Stroke(1.dp.toPx())
                    )
                }
            }
            .background(bg, shape)
            .border(1.dp, lerp(baseBorder, colors.accent, borderGlow.value * 0.65f), shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.5.dp)
            .animateContentSize(Motion.settle()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (sealedAsks.isNotEmpty()) {
            val lastAsk = sealedAsks.last()
            when (lastAsk.state) {
                AskState.ANSWERED -> StampGlyph("答", fresh = isFresh(lastAsk.answeredAt))
                else -> AskGlyph("问", colors.hint, small = true)
            }
            val askLabel = remember(sealedAsks, traces.isNotEmpty()) {
                askPillSummary(sealedAsks, hasTraces = traces.isNotEmpty())
            }
            if (askLabel.isNotEmpty()) {
                Text(
                    askLabel,
                    style = PillTextStyle,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (sealedAsks.isNotEmpty() && traces.isNotEmpty()) {
            Text("·", style = PillTextStyle, color = colors.rule2)
        }

        if (traces.isNotEmpty()) {
            val running = traces.lastOrNull { it.state == TraceState.RUNNING }
            val markState = when {
                running != null -> TraceState.RUNNING
                anyFailed -> TraceState.FAILED
                else -> TraceState.OK
            }
            TraceMark(markState, fresh = freshTrace)

            val summary = remember(traces) { tracePillSummary(traces) }
            Crossfade(
                targetState = (running != null) to summary,
                modifier = Modifier.weight(1f, fill = false).animateContentSize(Motion.settle()),
                animationSpec = tween(Motion.SHORT),
                label = "imprintTraceText"
            ) { (isRunning, text) ->
                if (isRunning) {
                    InkText(text, streaming = true, style = PillTextStyle, color = colors.ink2, caret = false)
                } else {
                    Text(
                        text,
                        style = PillTextStyle,
                        color = if (anyFailed) colors.red else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val turn by animateFloatAsState(if (expanded) 270f else 90f, Motion.settle(), label = "imprintChevron")
        ChevronRightIcon(Modifier.graphicsLayer { rotationZ = turn }, size = 10.dp, tint = colors.hint)
    }
}

/** 胶囊上「答」印旁边的简短字样 */
private fun askPillSummary(asks: List<TurnBlock.Ask>, hasTraces: Boolean): String {
    val last = asks.last()
    return when (last.state) {
        AskState.SAID -> if (hasTraces) "" else "直接说了"
        AskState.ANSWERED -> {
            val req = last.request ?: AskUserTool.draftOf(last.arguments)
            val total = req.questions.size
            val pickedCount = last.picks.count { it != null }
            val unit = if (req.questions.all { it.amount != null }) "笔" else "题"
            when {
                total == 1 && !hasTraces -> {
                    val q = req.questions.first()
                    val ans = answerLabelOf(q, last.picks.firstOrNull(), req.single) ?: "先放着"
                    if (q.amount != null) "${q.amount} $ans" else ans
                }
                total == 1 -> if (pickedCount == 0) "先放着" else "答 1"
                pickedCount == 0 -> "先放着"
                hasTraces -> "答 $pickedCount"
                else -> "答了 $pickedCount $unit"
            }
        }
        else -> "没答"
    }
}

/** 胶囊上「✓」旁边的简短摘要：单条展示标题，多条浓缩计数 */
private fun tracePillSummary(traces: List<TurnBlock.Trace>): String {
    val running = traces.lastOrNull { it.state == TraceState.RUNNING }
    if (running != null) {
        val v = traceViewOf(running)
        return if (v.text.isNotBlank()) "${v.working} · ${v.text}" else v.working
    }
    if (traces.size == 1) {
        val v = traceViewOf(traces.single())
        return if (v.text.isNotBlank()) "${v.settled} · ${v.text}" else v.settled
    }
    return traces.map { t ->
        val v = traceViewOf(t)
        if (t.state == TraceState.FAILED) v.settled
        else when (v.settled) {
            "记账" -> if (v.text.startsWith("改了 ")) v.text.substringBefore(" · ") else "记账"
            "记住了" -> if (v.lines.size > 1) "记住 ${v.lines.size} 条" else "记住 1 条"
            "忘了" -> "忘了 1 条"
            else -> v.settled
        }
    }.distinct().joinToString(" · ")
}
