package com.abc.daodian.assistant.conversation.input

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.CloseIcon
import kotlin.math.PI
import kotlin.math.sin

/** 波形 / 字框的下沿停在这一层多高的地方（从上往下数）：屏幕中间偏下，离手指够远、不被手挡住 */
private const val BOX_BOTTOM = 0.63f

/** 中间那条波形：刚按下 13 根，每秒多 2.2 根，32 根封顶（原型的数） */
private const val WAVE_MIN = 13
private const val WAVE_MAX = 32
private const val WAVE_PER_SECOND = 2.2f
private val WAVE_BAR = 3.dp
private val WAVE_GAP = 4.dp
private val WAVE_HEIGHT = 34.dp

/** 字框右下角那条小波形 */
private const val MINI_BARS = 9
private val MINI_BAR = 2.dp
private val MINI_GAP = 2.dp
private val MINI_HEIGHT = 14.dp

private val BoxShape = RoundedCornerShape(24.dp)
private val BoxText = DaodianType.cardTitle.copy(fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 32.sp)
private val Hint = DaodianType.speakerTag.copy(fontSize = 12.sp, letterSpacing = 0.2.em)
private val TargetLabel = DaodianType.speakerTag.copy(fontSize = 12.sp, letterSpacing = 0.2.em)
private val TargetGlyph = DaodianType.cardTitle.copy(fontWeight = FontWeight.Normal, fontSize = 21.sp)

/**
 * 按住说话时盖在对话上面的那一层（输入条不盖）。见 DESIGN.md §8.3「对话页：按住说话」，原型
 * https://claude.ai/artifact/VtJp6zsX1jJJSarSxQwY3U（11-04 定的）
 *
 * 对话淡下去，中间一条灰框里的波形跟着音量跳，越说越长（有上限）。底下左右两颗落点：「取消」「转文字」。
 * - 手指在中间：只有波形，原地松手发出去；
 * - 拖到右边：波形缩到字框右下角，中间出实时的字 —— 锁定的深、正在认的浅；松手发字；
 * - 拖到左边：波形变虚线，取消那颗变朱砂；松手什么都不发。
 */
@Composable
fun VoiceHoldOverlay(
    heard: String,
    /** [heard] 里前多少个字是锁定的，后面那截还在认 */
    locked: Int,
    level: Float,
    /** 手指在哪儿；松手之后是松手时那一格，好让框按原样淡掉 */
    zone: VoiceHold,
    /** 这一句不要了（拖到左边松手、按太短、切走）：淡掉那几帧照样画成虚线 */
    dropped: Boolean,
    /** 手还按着。松手之后等最后几个字解完的那一小会儿是 false：提示和落点都收了，只剩中间那块 */
    pressed: Boolean,
    /** 按了多久，波形照这个变长 */
    heldMillis: Long,
    modifier: Modifier = Modifier
) {
    val colors = DaodianColors.current
    val cancel = zone == VoiceHold.Cancel || dropped
    Column(modifier.background(colors.paper.copy(alpha = 0.84f))) {
        Box(
            Modifier.weight(BOX_BOTTOM).fillMaxWidth().padding(horizontal = 34.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Crossfade(zone == VoiceHold.Text && !dropped, animationSpec = tween(Motion.SHORT), label = "holdCenter") { text ->
                if (text) TranscriptBox(heard, locked, level)
                else WavePill(level, heldMillis, cancel)
            }
        }
        Box(Modifier.weight(1f - BOX_BOTTOM).fillMaxWidth()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = pressed,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(Motion.flow(Motion.SHORT)),
                exit = fadeOut(Motion.exit())
            ) {
                Box(Modifier.fillMaxSize()) {
                    Crossfade(
                        holdLabel(if (zone == VoiceHold.Idle) VoiceHold.Holding else zone),
                        animationSpec = tween(Motion.SHORT),
                        label = "holdHint",
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)
                    ) {
                        Text(it, style = Hint, color = if (zone == VoiceHold.Cancel) colors.accent else colors.muted)
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 30.dp).padding(bottom = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Target("取消", hot = zone == VoiceHold.Cancel, hotFill = colors.accent, hotInk = colors.paper, hotLabel = colors.accent) { tint ->
                            CloseIcon(size = 14.dp, tint = tint, strokeWidth = 1.6.dp)
                        }
                        Target("转文字", hot = zone == VoiceHold.Text, hotFill = colors.solid, hotInk = colors.onSolid, hotLabel = colors.ink) { tint ->
                            Text("文", style = TargetGlyph, color = tint)
                        }
                    }
                }
            }
        }
    }
}

/** 左右两颗落点：62dp 的圈，下面一行小字。手指到了那边，圈放大一点、换实心 */
@Composable
private fun Target(
    label: String,
    hot: Boolean,
    hotFill: Color,
    hotInk: Color,
    hotLabel: Color,
    glyph: @Composable (Color) -> Unit
) {
    val colors = DaodianColors.current
    val scale by animateFloatAsState(if (hot) 1.14f else 1f, tween(150), label = "targetScale")
    val fill by animateColorAsState(if (hot) hotFill else colors.surface, tween(150), label = "targetFill")
    val ring by animateColorAsState(if (hot) hotFill else colors.rule2, tween(150), label = "targetRing")
    val ink by animateColorAsState(if (hot) hotInk else colors.ink2, tween(150), label = "targetInk")
    val text by animateColorAsState(if (hot) hotLabel else colors.muted, tween(150), label = "targetLabel")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier
                .size(62.dp)
                .scale(scale)
                .background(fill, CircleShape)
                .border(1.dp, ring, CircleShape),
            contentAlignment = Alignment.Center
        ) { glyph(ink) }
        Text(label, style = TargetLabel, color = text)
    }
}

/** 手指在中间或左边：灰框里一条波形。拖到左边框变虚线、墨条变浅 */
@Composable
private fun WavePill(level: Float, heldMillis: Long, cancel: Boolean) {
    val colors = DaodianColors.current
    val shadow = colors.ink.copy(alpha = 0.3f)
    val bars = (WAVE_MIN + (heldMillis / 1000f * WAVE_PER_SECOND).toInt()).coerceAtMost(WAVE_MAX)
    Box(
        Modifier
            .then(
                if (cancel) Modifier.background(colors.paper, BoxShape).dashedBorder(colors.rule2, 24.dp)
                else Modifier
                    .shadow(8.dp, BoxShape, clip = false, ambientColor = shadow, spotColor = shadow)
                    .background(colors.rule, BoxShape)
                    .border(1.dp, colors.rule2, BoxShape)
            )
            // 多一根就宽一截，别一跳一跳
            .animateContentSize(Motion.flow(Motion.SHORT))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Waveform(level, bars, WAVE_BAR, WAVE_GAP, WAVE_HEIGHT, tint = if (cancel) colors.rule2 else colors.ink2)
    }
}

/**
 * 拖到右边：字框。锁定的字墨色，正在认的那截浅灰、可能还会改；最后一个朱砂光标。
 * 字少时框也有个最小的样子（200×76），右下角那条小波形不出框；说长了框变宽、往上长，到头了在里面滚
 */
@Composable
private fun TranscriptBox(heard: String, locked: Int, level: Float) {
    val colors = DaodianColors.current
    val shadow = colors.ink.copy(alpha = 0.3f)
    val caretAlpha by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
            RepeatMode.Restart
        ),
        label = "caretBlink"
    )
    val cut = locked.coerceIn(0, heard.length)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.ink)) { append(heard.substring(0, cut)) }
        withStyle(SpanStyle(color = colors.hint)) { append(heard.substring(cut)) }
        appendInlineContent(CARET, "|")
    }
    val caret = mapOf(
        CARET to InlineTextContent(Placeholder(5.sp, 22.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .size(width = 1.5.dp, height = 22.dp)
                        .graphicsLayer { alpha = caretAlpha }
                        .background(colors.accent)
                )
            }
        }
    )
    Box(
        Modifier
            .sizeIn(minWidth = 200.dp, minHeight = 76.dp)
            .widthIn(max = 290.dp)
            .shadow(8.dp, BoxShape, clip = false, ambientColor = shadow, spotColor = shadow)
            .background(colors.rule, BoxShape)
            .border(1.dp, colors.rule2, BoxShape)
            .animateContentSize(Motion.flow(Motion.SHORT))
    ) {
        // 说得再长框也不顶出屏幕：到头了里面往上滚，最新的字一直看得见
        val scroll = rememberScrollState()
        LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
        Box(
            Modifier
                .heightIn(max = 256.dp)
                .verticalScroll(scroll)
                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 30.dp)
        ) {
            Text(text, style = BoxText, inlineContent = caret)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 9.dp)) {
            Waveform(level, MINI_BARS, MINI_BAR, MINI_GAP, MINI_HEIGHT, tint = colors.muted)
        }
    }
}

private const val CARET = "caret"

/**
 * 一排墨条：一道波从左往右过，幅度跟着音量 [level]（0..1）。每根的高低是定的（中间高两头低再掺点起伏），
 * 根数变了已经有的那几根不跳。没声音时只剩很浅的起伏 —— 看得出在听，也看得出没听到什么。
 * 无限循环只在这排墨条在的时候跑（§6.5）
 */
@Composable
private fun Waveform(level: Float, bars: Int, bar: Dp, gap: Dp, height: Dp, tint: Color) {
    val amp by animateFloatAsState(level, tween(120, easing = LinearEasing), label = "waveAmp")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "wavePhase"
    )
    val width = bar * bars + gap * (bars - 1)
    Canvas(Modifier.size(width = width, height = height)) {
        val w = bar.toPx()
        val step = w + gap.toPx()
        for (i in 0 until bars) {
            val peak = size.height * PEAKS[i % PEAKS.size]
            val swing = 0.55f + 0.45f * sin(phase - i * 0.55f)
            val h = (peak * (0.2f + 0.1f * swing + 0.7f * amp * swing)).coerceAtLeast(w)
            drawRoundRect(
                color = tint,
                topLeft = Offset(i * step, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2)
            )
        }
    }
}

/** 每根墨条最高占多高（相对整排的高度），一组 11 根轮着用 */
private val PEAKS = floatArrayOf(0.45f, 0.75f, 0.55f, 1f, 0.65f, 0.35f, 0.85f, 0.5f, 0.95f, 0.6f, 0.4f)

private fun Modifier.dashedBorder(color: Color, corner: Dp) = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(corner.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    )
}
