package com.abc.daodian.assistant.conversation.turn

import com.abc.daodian.assistant.conversation.TurnBlock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.assistant.memory.MemoryTrace
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.assistant.plugin.ToolTrace
import com.abc.daodian.assistant.plugin.TraceState
import com.abc.daodian.assistant.plugin.TraceView
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion

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

internal val PillTextStyle = DaodianType.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
internal val DrawerLabelStyle = DaodianType.speakerTag.copy(fontSize = 11.sp, lineHeight = 19.sp, letterSpacing = 0.14.em)
internal val DrawerBodyStyle = DaodianType.caption.copy(fontSize = 12.5.sp, lineHeight = 19.sp, fontFeatureSettings = "tnum")

/**
 * 伴随印里的状态记号：在办时是朱砂圆点外一圈呼吸的晕；办成了圆点缩掉、对勾一笔描出来；没办成是红色 ×。
 * 晕是无限循环的动画，只在「在办」时进组合 —— 挂着不用也会一直要帧。
 */
@Composable
internal fun TraceMark(state: TraceState, fresh: Boolean) {
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
