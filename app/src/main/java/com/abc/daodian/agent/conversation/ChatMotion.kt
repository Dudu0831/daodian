package com.abc.daodian.agent.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.Motion
import kotlin.math.PI
import kotlin.math.cos

/*
 * 对话里那几样会自己动的小东西。时长曲线一律取 Motion，见 DESIGN.md 决策 6.3。
 * 无限循环的动画只在真正需要时才进组合 —— 挂着不用也会一直要帧、一直耗电。
 */

/** 一段字的到货记录：每段增量从哪个下标开始、什么时候到的 */
private class InkLog(var len: Int) {
    val starts = ArrayList<Int>()
    val times = ArrayList<Long>()
}

private const val CARET = "▍"
private const val CARET_STEADY_MS = 400f
private const val CARET_PERIOD_MS = 1060f

/**
 * 逐字洇开的一段字：每段增量 [Motion.CHAR] ms 从淡到实，不是一下蹦出来。
 * [caret] 为 true 时末尾跟一个墨块光标 —— 字在流的时候常亮，停顿超过 400ms 才开始闪，
 * 一直闪的光标分不清「在写」和「卡住了」。
 *
 * 流完了（[streaming] = false）就是一段普通的 Text，不再逐帧重组。
 */
@Composable
fun InkText(
    text: String,
    streaming: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    caret: Boolean = false,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    // 进组合时已经在的字不算「刚到」—— 列表滚回来重组时不该再洇一遍
    val log = remember { InkLog(text.length) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(text) {
        if (text.length < log.len) {
            // 回退擦掉了：从头记
            log.starts.clear()
            log.times.clear()
            log.len = 0
        }
        if (text.length > log.len) {
            log.starts += log.len
            log.times += System.nanoTime()
            log.len = text.length
        }
    }
    LaunchedEffect(streaming) {
        if (streaming) while (true) withFrameNanos { now = it }
    }

    if (!streaming) {
        Text(text, modifier, style = style, color = color, onTextLayout = onTextLayout)
        return
    }

    val annotated = buildAnnotatedString {
        var cursor = 0
        for (i in log.starts.indices) {
            val start = log.starts[i].coerceIn(cursor, text.length)
            val end = (log.starts.getOrNull(i + 1) ?: text.length).coerceIn(start, text.length)
            if (start > cursor) append(text.substring(cursor, start))
            if (end > start) {
                val age = (now - log.times[i]) / 1_000_000f
                val a = (0.1f + 0.9f * age / Motion.CHAR).coerceIn(0.1f, 1f)
                if (a >= 1f) append(text.substring(start, end))
                else withStyle(SpanStyle(color = color.copy(alpha = color.alpha * a))) { append(text.substring(start, end)) }
            }
            cursor = end
        }
        // LaunchedEffect 还没来得及记的那一截：刚到，按最淡画
        if (cursor < text.length) {
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * 0.1f))) { append(text.substring(cursor)) }
        }
        if (caret) {
            val since = ((now - (log.times.lastOrNull() ?: now)) / 1_000_000f).coerceAtLeast(0f)
            val a = if (since < CARET_STEADY_MS) 1f
            else 0.5f + 0.5f * cos(2 * PI * (since - CARET_STEADY_MS) / CARET_PERIOD_MS).toFloat()
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * a))) { append(CARET) }
        }
    }
    Text(annotated, modifier, style = style, color = color, onTextLayout = onTextLayout)
}

/**
 * 请求发出去了、一个字都还没回来：两根墨条，一道墨色从左往右洇过去。
 * 替换原来三根条的整体明暗 —— 整体明暗读作「加载中」，洇染读作「字正在成形」。
 */
@Composable
fun InkWashBars(modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    val phase by rememberInfiniteTransition(label = "wash").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.WASH, easing = LinearEasing)),
        label = "washPhase"
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WashBar(0.78f, { phase }, colors.skeleton, colors.skeletonHi)
        WashBar(0.46f, { (phase + 0.87f) % 1f }, colors.skeleton, colors.skeletonHi)
    }
}

/** 相位用 lambda 传进来，只在 draw 阶段读 —— 每帧重画，不每帧重组 */
@Composable
private fun WashBar(fraction: Float, phase: () -> Float, base: Color, hi: Color) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .drawBehind {
                val band = size.width * 0.7f
                val center = -band / 2 + phase() * (size.width + band)
                drawRect(
                    Brush.horizontalGradient(
                        listOf(base, hi, base),
                        startX = center - band / 2,
                        endX = center + band / 2
                    )
                )
            }
    )
}

/** 顶部 [height] 那一截渐隐。只在内容真的溢出时才开，否则一行字会被自己的遮罩吃掉 */
fun Modifier.fadeTop(height: Dp, enabled: Boolean): Modifier =
    if (!enabled) this
    else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = height.toPx()),
                blendMode = BlendMode.DstIn
            )
        }
