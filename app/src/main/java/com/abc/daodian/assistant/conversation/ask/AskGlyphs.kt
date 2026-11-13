package com.abc.daodian.assistant.conversation.ask

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 印上的一个字：描边方框，不填色。卡头的「问」18dp；伴随印里的 13dp（[small]） */
@Composable
internal fun AskGlyph(text: String, tint: Color, modifier: Modifier = Modifier, small: Boolean = false) {
    val size = if (small) 13.dp else 18.dp
    val font: TextUnit = if (small) 9.sp else 11.sp
    val corner = if (small) 2.5.dp else 3.dp
    Box(modifier.size(size).border(1.dp, tint, RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        Text(text, style = DaodianType.seal.copy(fontSize = font, lineHeight = font), color = tint)
    }
}

/**
 * 答完那一刻在抬头伴随印上盖下来的「答」：从 1.45 倍、−10° 带回弹落下，印边洇开一圈。
 */
@Composable
internal fun StampGlyph(text: String, fresh: Boolean) {
    val colors = DaodianColors.current
    val scale = remember { Animatable(if (fresh) 1.45f else 1f) }
    val rotation = remember { Animatable(if (fresh) -10f else 0f) }
    val alpha = remember { Animatable(if (fresh) 0f else 1f) }
    val bleed = remember { Animatable(1f) }
    LaunchedEffect(fresh) {
        if (!fresh) return@LaunchedEffect
        scale.snapTo(1.45f)
        rotation.snapTo(-10f)
        alpha.snapTo(0f)
        bleed.snapTo(0f)
        launch { scale.animateTo(1f, Motion.stamp()) }
        launch { rotation.animateTo(0f, Motion.stamp()) }
        launch { alpha.animateTo(1f, tween(Motion.SHORT)) }
        launch {
            delay(180)
            bleed.animateTo(1f, tween(640, easing = LinearOutSlowInEasing))
        }
    }
    Box(
        Modifier
            .size(13.dp)
            .drawBehind {
                val b = bleed.value
                if (b < 1f) {
                    val grow = 5.dp.toPx() * b
                    drawRoundRect(
                        colors.accent.copy(alpha = 0.42f * (1f - b)),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(2.5.dp.toPx() + grow),
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
    ) {
        AskGlyph(text, colors.accent, small = true)
    }
}
