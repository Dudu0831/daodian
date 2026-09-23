package com.abc.daodian.agent.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.feature.ToolTrace
import com.abc.daodian.agent.feature.TraceState
import com.abc.daodian.agent.feature.TraceView
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.ChevronRightIcon

/*
 * 痕：一次写操作在对话里留下的一行小字。见 DESIGN.md §6.9，动效稿「动手」「办成」「没办成」三拍。
 *
 * 它是代码按工具结果画的，不是模型说的 —— 模型嘴上说「记下了」却没调工具，这里就是空的。
 * 所以它不能省：真机上出过只回一句「明白」、什么都没建的情形。
 * 字怎么写归模块（Feature.trace），这里只管画和点了去哪（[TraceView.route]）。
 */

/** 痕上写什么由它的模块决定；没有模块认领（不该发生）就只写工具名 */
fun traceViewOf(block: TurnBlock.Trace): TraceView =
    FeatureRegistry.trace(ToolTrace(block.tool, block.arguments, block.state, block.output, block.ref))
        ?: TraceView("在办", if (block.state == TraceState.FAILED) "没办成" else "办了", block.tool)

/** 对勾只在刚办成时描一次；列表滚回来、重启读回来的都直接画好 */
private fun isFresh(at: Long) = System.currentTimeMillis() - at < 1_500

@Composable
fun TraceLine(block: TurnBlock.Trace, onOpen: (String) -> Unit, onToggle: () -> Unit) {
    val colors = DaodianColors.current
    val view = remember(block.state, block.arguments, block.output, block.ref) { traceViewOf(block) }
    val expandable = view.lines.isNotEmpty()
    val tappable = block.state == TraceState.OK && (expandable || view.route != null)
    val failed = block.state == TraceState.FAILED
    val tone by animateColorAsState(if (failed) colors.red else colors.ink2, Motion.flow(Motion.SHORT), label = "traceTone")
    val labelTone by animateColorAsState(if (failed) colors.red else colors.muted, Motion.flow(Motion.SHORT), label = "traceLabel")

    Column {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clickable(enabled = tappable) { if (expandable) onToggle() else view.route?.let(onOpen) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TraceMark(block.state, fresh = isFresh(block.doneAt))
            // 「在记提醒」比「提醒」宽：宽度跟着收，不然换完字中间空一截
            Crossfade(
                if (block.state == TraceState.RUNNING) view.working else view.settled,
                modifier = Modifier.animateContentSize(Motion.flow()),
                animationSpec = tween(Motion.SHORT),
                label = "traceKind"
            ) {
                Text(it, style = DaodianType.speakerTag.copy(fontSize = 11.sp, letterSpacing = 0.2.em), color = labelTone)
            }
            Crossfade(block.state == TraceState.RUNNING, animationSpec = tween(120), label = "traceText", modifier = Modifier.weight(1f, fill = false)) { running ->
                if (running) {
                    InkText(view.text, streaming = true, style = DaodianType.bodySmall, color = tone, caret = false)
                } else {
                    Text(view.text, style = DaodianType.bodySmall, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tappable) {
                val turn by animateFloatAsState(if (expandable) (if (block.expanded) 270f else 90f) else 0f, Motion.flow(), label = "traceChevron")
                ChevronRightIcon(Modifier.graphicsLayer { rotationZ = turn }, size = 12.dp, tint = colors.hint)
            }
        }
        AnimatedVisibility(
            visible = expandable && block.expanded,
            enter = expandVertically(Motion.settle()) + fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.exit())
        ) {
            Column(Modifier.padding(start = 22.dp, bottom = 4.dp)) {
                view.lines.forEach { Text(it, style = DaodianType.bodySmall.copy(lineHeight = 22.sp), color = colors.ink2) }
            }
        }
    }
}

/**
 * 痕最左边那个记号：在办时是朱砂圆点外一圈呼吸的晕；办成了圆点缩掉、对勾一笔描出来；没办成是红色 ×。
 * 晕是无限循环的动画，只在「在办」时进组合 —— 挂着不用也会一直要帧。
 */
@Composable
private fun TraceMark(state: TraceState, fresh: Boolean) {
    val colors = DaodianColors.current
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
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
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            val s = 0.55f + 0.45f * halo
                            scaleX = s
                            scaleY = s
                            alpha = 0.3f - 0.2f * halo
                        }
                        .background(colors.accent, CircleShape)
                )
                Box(Modifier.size(6.dp).background(colors.accent, CircleShape))
            }
        }
        if (state == TraceState.OK) {
            val draw = remember { Animatable(if (fresh) 0f else 1f) }
            LaunchedEffect(Unit) { if (fresh) draw.animateTo(1f, Motion.settle(Motion.MID, delay = 80)) }
            val tint = colors.accent
            Canvas(Modifier.size(14.dp)) {
                val u = size.width / 24f
                val path = Path().apply {
                    moveTo(4f * u, 12.5f * u)
                    lineTo(9f * u, 17.5f * u)
                    lineTo(20f * u, 6.5f * u)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * draw.value, part, true)
                drawPath(part, tint, style = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (state == TraceState.FAILED) {
            val tint = colors.red
            Canvas(Modifier.size(14.dp)) {
                val u = size.width / 24f
                val w = 2.2f * u
                drawLine(tint, Offset(6 * u, 6 * u), Offset(18 * u, 18 * u), w, StrokeCap.Round)
                drawLine(tint, Offset(18 * u, 6 * u), Offset(6 * u, 18 * u), w, StrokeCap.Round)
            }
        }
    }
}
