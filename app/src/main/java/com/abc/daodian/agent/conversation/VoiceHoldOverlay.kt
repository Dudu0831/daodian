package com.abc.daodian.agent.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.ChevronsUpIcon
import com.abc.daodian.shared.ui.CloseIcon
import kotlin.math.PI
import kotlin.math.sin

/** 文字框的下沿停在这一层多高的地方（从上往下数）：屏幕中间偏下，离手指够远、不被手挡住 */
private const val BOX_BOTTOM = 0.63f

/** 波形每根墨条最高多少 dp。中间高两头低，一共十五根 */
private val WAVE_PEAKS = floatArrayOf(6f, 10f, 16f, 22f, 14f, 8f, 18f, 26f, 18f, 8f, 14f, 22f, 16f, 10f, 6f)
private val WAVE_BAR = 3.dp
private val WAVE_GAP = 4.dp
private val WAVE_HEIGHT = 34.dp

private val BoxShape = RoundedCornerShape(24.dp)
private val BoxText = DaodianType.cardTitle.copy(fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 32.sp)
private val Hint = DaodianType.speakerTag.copy(fontSize = 12.sp, letterSpacing = 0.2.em)

/**
 * 按住说话时盖在对话上面的那一层（输入条不盖）。见 DESIGN.md §8.3「对话页：按住说话」，设计稿
 * https://claude.ai/artifact/UHQr2CCkjHLWdRiaAxd8Yz
 *
 * 对话淡下去，屏幕中间一个灰色文字框：还没出字是一排跟着音量走的波形，出了字就只有字，
 * 框跟着字变宽、到头了往上长。灰是还没发的，发出去才变成对话里那种墨色气泡。
 *
 * 取消只有一行字：输入条上方「上滑取消」。手指滑上去之后（[armed]）那行字被手盖住了，
 * 所以「松手取消」写在文字框下面，框变虚线、字划掉。
 */
@Composable
fun VoiceHoldOverlay(
    heard: String,
    level: Float,
    armed: Boolean,
    /** 手还按着。松手之后等最后几个字解完的那一小会儿是 false：提示都收了，只剩文字框 */
    pressed: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = DaodianColors.current
    Column(modifier.background(colors.paper.copy(alpha = 0.84f))) {
        Box(
            Modifier.weight(BOX_BOTTOM).fillMaxWidth().padding(horizontal = 45.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            TranscriptBox(heard, level, armed)
        }
        Box(Modifier.weight(1f - BOX_BOTTOM).fillMaxWidth()) {
            if (armed) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CloseIcon(tint = colors.accent, strokeWidth = 1.5.dp)
                    Text("松手取消", style = Hint.copy(fontSize = 13.sp), color = colors.accent)
                }
            } else if (pressed) {
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ChevronsUpIcon(tint = colors.hint)
                    Text("上滑取消", style = Hint, color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun TranscriptBox(heard: String, level: Float, armed: Boolean) {
    val colors = DaodianColors.current
    val shadow = colors.ink.copy(alpha = 0.3f)
    Box(
        Modifier
            .widthIn(max = 300.dp)
            .then(
                if (armed) Modifier.background(colors.paper, BoxShape).dashedBorder(colors.rule2, 24.dp)
                else Modifier
                    .shadow(8.dp, BoxShape, clip = false, ambientColor = shadow, spotColor = shadow)
                    .background(colors.rule, BoxShape)
                    .border(1.dp, colors.rule2, BoxShape)
            )
            // 字一截一截到，框别跟着一跳一跳
            .animateContentSize(Motion.flow(Motion.SHORT))
            .padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        if (heard.isEmpty()) {
            Waveform(level, tint = if (armed) colors.rule2 else colors.ink2)
        } else {
            // 说得再长框也不顶出屏幕：到头了里面往上滚，最新的字一直看得见
            val scroll = rememberScrollState()
            LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
            Box(Modifier.heightIn(max = 256.dp).verticalScroll(scroll)) {
                if (armed) Text(heard, style = BoxText.copy(textDecoration = TextDecoration.LineThrough), color = colors.hint)
                else InkText(heard, streaming = true, style = BoxText, color = colors.ink, caret = true)
            }
        }
    }
}

/**
 * 还没出字时框里的那排墨条：一道波从左往右过，幅度跟着音量 [level]（0..1）。
 * 没声音时只剩很浅的起伏 —— 看得出在听，也看得出没听到什么。
 * 无限循环只在这排墨条在的时候跑（§6.5），一出字它就退出组合了
 */
@Composable
private fun Waveform(level: Float, tint: Color) {
    val amp by animateFloatAsState(level, tween(120, easing = LinearEasing), label = "waveAmp")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "wavePhase"
    )
    val width = WAVE_BAR * WAVE_PEAKS.size + WAVE_GAP * (WAVE_PEAKS.size - 1)
    Canvas(Modifier.size(width = width, height = WAVE_HEIGHT)) {
        val bar = WAVE_BAR.toPx()
        val step = bar + WAVE_GAP.toPx()
        WAVE_PEAKS.forEachIndexed { i, peak ->
            val swing = 0.55f + 0.45f * sin(phase - i * 0.55f)
            val h = (peak.dp.toPx() * (0.2f + 0.1f * swing + 0.7f * amp * swing)).coerceAtLeast(bar)
            drawRoundRect(
                color = tint,
                topLeft = Offset(i * step, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2)
            )
        }
    }
}

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
