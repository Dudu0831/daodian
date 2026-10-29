package com.abc.daodian.assistant.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.assistant.memory.MemoryTrace
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.runtime.ask.AskRequest
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianPalette
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.ChevronRightIcon
import kotlinx.coroutines.launch

/*
 * 抬头伴随印（款式三）：答完的问卡 + 一次或多次写操作留下的痕，合拢成一枚轻量胶囊挂在「· 到点」右侧。
 * 正文区不插队、不铺大块列表；点一下伴随印，在抬头下展开细目抽屉（[TurnImprintDrawer]）。
 *
 * 它是代码按工具结果与问卡答案画的，不是模型说的 —— 模型嘴上说「记下了」却没调工具，这里就是空的。
 */

/** 痕上写什么由它的模块决定（记忆是 agent 自己的，先问它）；没人认领（不该发生）就只写工具名 */
fun traceViewOf(block: TurnBlock.Trace): TraceView =
    ToolTrace(block.tool, block.arguments, block.state, block.output, block.ref).let { MemoryTrace.of(it) ?: AssistantPlugins.trace(it) }
        ?: TraceView("在办", if (block.state == TraceState.FAILED) "没办成" else "办了", block.tool)

/** 对勾与落印只在刚完成时演一次；列表滚回来、重启读回来的都直接落定 */
internal fun isFresh(at: Long) = at > 0L && System.currentTimeMillis() - at < 1_500

private val PillTextStyle = DaodianType.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
private val DrawerLabelStyle = DaodianType.speakerTag.copy(fontSize = 11.sp, lineHeight = 19.sp, letterSpacing = 0.14.em)
private val DrawerBodyStyle = DaodianType.caption.copy(fontSize = 12.5.sp, lineHeight = 19.sp, fontFeatureSettings = "tnum")

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

/**
 * 点开伴随印后，在「· 到点」下方展开的轻量细目抽屉：左边一道 1.5dp 细墨线，逐条列出答复与写操作。
 * 带 [TraceView.route] 的行整行可点，末尾带 `›`。
 */
@Composable
internal fun TurnImprintDrawer(
    sealedAsks: List<TurnBlock.Ask>,
    traces: List<TurnBlock.Trace>,
    onOpen: (String) -> Unit
) {
    val colors = DaodianColors.current
    Column(
        Modifier
            .drawBehind {
                drawLine(
                    colors.rule2,
                    Offset(0.75.dp.toPx(), 2.dp.toPx()),
                    Offset(0.75.dp.toPx(), size.height - 2.dp.toPx()),
                    1.5.dp.toPx(),
                    StrokeCap.Round
                )
            }
            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        sealedAsks.forEach { ask ->
            val req = remember(ask.request, ask.arguments) { ask.request ?: AskUserTool.draftOf(ask.arguments) }
            val label = when (ask.state) {
                AskState.ANSWERED -> "答了"
                AskState.SAID -> "问过"
                else -> "没答"
            }
            val labelTone = if (ask.state == AskState.ANSWERED) colors.accent else colors.muted
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = DrawerLabelStyle, color = labelTone, modifier = Modifier.widthIn(min = 34.dp))
                Text(drawerAskText(ask, req, colors), style = DrawerBodyStyle, modifier = Modifier.weight(1f, fill = false))
            }
        }

        traces.forEach { trace ->
            val view = remember(trace.state, trace.arguments, trace.output, trace.ref) { traceViewOf(trace) }
            val failed = trace.state == TraceState.FAILED
            val running = trace.state == TraceState.RUNNING
            val route = view.route?.takeIf { trace.state == TraceState.OK }
            val labelTone = when {
                failed -> colors.red
                running -> colors.muted
                else -> colors.accent
            }
            val bodyTone = if (failed) colors.red else colors.ink2

            Row(
                Modifier.clickable(enabled = route != null) { route?.let(onOpen) },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (running) view.working else view.settled,
                    style = DrawerLabelStyle,
                    color = labelTone,
                    modifier = Modifier.widthIn(min = 34.dp)
                )
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (view.lines.isNotEmpty()) {
                        view.lines.forEach { line ->
                            Text(line, style = DrawerBodyStyle, color = bodyTone)
                        }
                    } else {
                        val content = view.text.ifBlank { if (running) "…" else view.settled }
                        Text(
                            buildAnnotatedString {
                                append(content)
                                if (route != null) {
                                    withStyle(SpanStyle(color = colors.hint)) { append(" ›") }
                                }
                            },
                            style = DrawerBodyStyle,
                            color = bodyTone
                        )
                    }
                }
            }
        }
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

/** 抽屉里一行展示的问卡答复细目 */
private fun drawerAskText(ask: TurnBlock.Ask, req: AskRequest, colors: DaodianPalette) = buildAnnotatedString {
    when (ask.state) {
        AskState.SAID -> {
            val asked = req.questions.mapNotNull { it.prompt ?: it.amount ?: it.context }.joinToString("、").trimEnd('？', '?')
            withStyle(SpanStyle(color = colors.muted)) {
                append(if (asked.isNotEmpty()) "$asked · 你直接说了" else "你直接说了")
            }
        }
        AskState.UNANSWERED -> {
            val asked = req.questions.mapNotNull { it.prompt ?: it.amount ?: it.context }.joinToString("、").trimEnd('？', '?')
            withStyle(SpanStyle(color = colors.muted)) {
                append(if (asked.isNotEmpty()) "$asked · 没答" else "没答")
            }
        }
        else -> {
            req.questions.forEachIndexed { i, q ->
                if (i > 0) withStyle(SpanStyle(color = colors.rule2)) { append("  ·  ") }
                val ans = answerLabelOf(q, ask.picks.getOrNull(i), req.single)
                val note = ask.notes.getOrNull(i)?.short
                val amount = q.amount
                if (amount != null) {
                    withStyle(SpanStyle(color = colors.muted)) { append("$amount ") }
                    withStyle(SpanStyle(color = if (ans == null) colors.muted else colors.ink2)) { append(ans ?: "先放着") }
                    if (note != null) withStyle(SpanStyle(color = colors.muted)) { append("($note)") }
                } else {
                    val prompt = (q.prompt ?: q.context).orEmpty().trimEnd('？', '?')
                    if (prompt.isNotEmpty()) withStyle(SpanStyle(color = colors.muted)) { append("$prompt ") }
                    withStyle(SpanStyle(color = if (ans == null) colors.muted else colors.ink2)) { append(ans ?: "先放着") }
                    if (note != null) withStyle(SpanStyle(color = colors.muted)) { append(" · $note") }
                }
            }
        }
    }
}

/**
 * 伴随印里的状态记号：在办时是朱砂圆点外一圈呼吸的晕；办成了圆点缩掉、对勾一笔描出来；没办成是红色 ×。
 * 晕是无限循环的动画，只在「在办」时进组合 —— 挂着不用也会一直要帧。
 */
@Composable
private fun TraceMark(state: TraceState, fresh: Boolean) {
    val colors = DaodianColors.current
    Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = state == TraceState.RUNNING,
            enter = fadeIn(Motion.flow()),
            exit = scaleOut(Motion.exit()) + fadeOut(Motion.exit())
        ) {
            val halo by rememberInfiniteTransition(label = "halo").animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                label = "haloP"
            )
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(12.dp)
                        .graphicsLayer {
                            val s = 0.55f + 0.45f * halo
                            scaleX = s
                            scaleY = s
                            alpha = 0.3f - 0.2f * halo
                        }
                        .background(colors.accent, CircleShape)
                )
                Box(Modifier.size(5.dp).background(colors.accent, CircleShape))
            }
        }
        if (state == TraceState.OK) {
            val draw = remember { Animatable(if (fresh) 0f else 1f) }
            LaunchedEffect(fresh) {
                if (fresh) {
                    draw.snapTo(0f)
                    draw.animateTo(1f, Motion.settle(Motion.MID, delay = 60))
                }
            }
            val tint = colors.accent
            Canvas(Modifier.size(12.dp)) {
                val u = size.width / 24f
                val path = Path().apply {
                    moveTo(4f * u, 12.5f * u)
                    lineTo(9f * u, 17.5f * u)
                    lineTo(20f * u, 6.5f * u)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * draw.value, part, true)
                drawPath(part, tint, style = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (state == TraceState.FAILED) {
            val tint = colors.red
            Canvas(Modifier.size(12.dp)) {
                val u = size.width / 24f
                val w = 2.4f * u
                drawLine(tint, Offset(6 * u, 6 * u), Offset(18 * u, 18 * u), w, StrokeCap.Round)
                drawLine(tint, Offset(18 * u, 6 * u), Offset(6 * u, 18 * u), w, StrokeCap.Round)
            }
        }
    }
}
