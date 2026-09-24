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
import androidx.compose.ui.text.AnnotatedString
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
internal class InkLog(var len: Int) {
    val starts = ArrayList<Int>()
    val times = ArrayList<Long>()
}

private const val CARET = "▍"
private const val CARET_STEADY_MS = 400f
private const val CARET_PERIOD_MS = 1060f

/**
 * 这一帧每个字该多淡。下标是**原文**的 —— Markdown 去掉标记符之后，字拿自己的原文下标来这里查（[MdRun.src]）。
 */
internal class InkFrame(private val log: InkLog, private val now: Long) {

    /** 原文第 [i] 个字的不透明度：进组合时已经在的字是 1；还没记上的（刚到）按最淡画 */
    fun alpha(i: Int): Float {
        if (i >= log.len) return 0.1f
        val k = batchOf(i)
        if (k < 0) return 1f
        val age = (now - log.times[k]) / 1_000_000f
        return (0.1f + 0.9f * age / Motion.CHAR).coerceIn(0.1f, 1f)
    }

    /** [from, to) 里各批之间的分界（不含两端）：一段一段上色用 */
    fun cuts(from: Int, to: Int): List<Int> {
        val out = ArrayList<Int>()
        var k = batchOf(from) + 1
        while (k < log.starts.size && log.starts[k] < to) {
            if (log.starts[k] > from) out += log.starts[k]
            k++
        }
        if (log.len in (from + 1) until to && out.lastOrNull() != log.len) out += log.len
        return out
    }

    /** 光标：字在流的时候常亮，停顿超过 400ms 才开始闪 —— 一直闪的光标分不清「在写」和「卡住了」 */
    val caretAlpha: Float
        get() {
            val since = ((now - (log.times.lastOrNull() ?: now)) / 1_000_000f).coerceAtLeast(0f)
            return if (since < CARET_STEADY_MS) 1f
            else 0.5f + 0.5f * cos(2 * PI * (since - CARET_STEADY_MS) / CARET_PERIOD_MS).toFloat()
        }

    /** 第 [i] 个字属于第几批（最后一个起点不超过它的那批）；比第一批还早就是 -1 */
    private fun batchOf(i: Int): Int {
        var lo = 0
        var hi = log.starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (log.starts[mid] <= i) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

/**
 * 记下 [text] 每段增量的到货时刻，流着的时候每帧给一个 [InkFrame]；流完了是 null，一律实画、不再逐帧重组。
 * 进组合时已经在的字不算「刚到」—— 列表滚回来重组时不该再洇一遍。
 */
@Composable
internal fun rememberInk(text: String, streaming: Boolean): InkFrame? {
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
    return if (streaming) InkFrame(log, now) else null
}

/** 原文从 [src] 起的这段字，按到货先后一段段淡入。[ink] 为 null（流完了）就原样接上 */
internal fun AnnotatedString.Builder.appendInk(s: String, src: Int, color: Color, ink: InkFrame?) {
    if (ink == null || s.isEmpty()) {
        append(s)
        return
    }
    var p = src
    val end = src + s.length
    for (q in ink.cuts(src, end) + end) {
        val a = ink.alpha(p)
        val piece = s.substring(p - src, q - src)
        if (a >= 1f) append(piece)
        else withStyle(SpanStyle(color = color.copy(alpha = color.alpha * a))) { append(piece) }
        p = q
    }
}

internal fun AnnotatedString.Builder.appendCaret(color: Color, ink: InkFrame?) {
    val a = ink?.caretAlpha ?: 1f
    withStyle(SpanStyle(color = color.copy(alpha = color.alpha * a))) { append(CARET) }
}

/**
 * 逐字洇开的一段字：每段增量 [Motion.CHAR] ms 从淡到实，不是一下蹦出来。
 * [caret] 为 true 时末尾跟一个墨块光标（见 [InkFrame.caretAlpha]）。
 *
 * 流完了（[streaming] = false）就是一段普通的 Text，不再逐帧重组。模型的正文走 [MarkdownText]，同一套洇法。
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
    val ink = rememberInk(text, streaming)
    if (ink == null) {
        Text(text, modifier, style = style, color = color, onTextLayout = onTextLayout)
        return
    }
    val annotated = buildAnnotatedString {
        appendInk(text, 0, color, ink)
        if (caret) appendCaret(color, ink)
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

/** 右边 [width] 那一截渐隐：横着放不下、还能往右滑的时候提示一下 */
fun Modifier.fadeEnd(width: Dp, enabled: Boolean): Modifier =
    if (!enabled) this
    else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - width.toPx(), endX = size.width),
                blendMode = BlendMode.DstIn
            )
        }
