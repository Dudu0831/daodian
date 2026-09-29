package com.abc.daodian.shared.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.Motion
import kotlin.math.hypot
import kotlin.math.max

/*
 * 墨洇的小块：问卡的猜测、打标签的标签共用。点下去墨从指尖洇满，取消原路退回。
 * 动效稿：https://claude.ai/artifact/BTqaHuU6hbgjmG6NqPv5HP（问卡）、
 * https://claude.ai/artifact/7Cx7HtXU1pVkXn3ghwhXQm（打标签）
 */

/** 猜测上的字色：墨色洇满之后翻成纸色 */
class InkTone(val fg: Color, val sub: Color)

/**
 * 一颗猜测。按下缩到 0.96；选中时墨色以手指落下的地方为圆心洇满整颗，取消时原路退回。
 * 同一题别的猜测淡到一半（[dimmed]），还能改。
 */
@Composable
fun InkChip(
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
    content: @Composable (InkTone) -> Unit
) {
    val colors = DaodianColors.current
    val interaction = remember { MutableInteractionSource() }
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Press) origin = it.pressPosition }
    }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.flow(Motion.SHORT), label = "chipPress")
    val alpha by animateFloatAsState(if (dimmed) 0.5f else 1f, Motion.flow(), label = "chipDim")
    val fill = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { fill.animateTo(if (selected) 1f else 0f, if (selected) Motion.settle() else Motion.flow()) }
    val fg by animateColorAsState(if (selected) colors.onSolid else colors.ink, Motion.flow(), label = "chipFg")
    val sub by animateColorAsState(if (selected) colors.onSolid.copy(alpha = 0.72f) else colors.muted, Motion.flow(), label = "chipSub")
    val edge by animateColorAsState(
        when {
            selected -> colors.solid
            accent -> colors.accent
            else -> colors.rule2
        },
        Motion.flow(Motion.SHORT), label = "chipEdge"
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(shape)
            .drawBehind {
                val f = fill.value
                if (f > 0f) {
                    val o = if (origin.isSpecified) origin else center
                    val reach = hypot(max(o.x, size.width - o.x), max(o.y, size.height - o.y))
                    drawCircle(colors.solid, radius = reach * f, center = o)
                }
            }
            .then(if (dashed) Modifier.dashedBorder(edge, size = 17.dp) else Modifier.border(1.dp, edge, shape))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        content(InkTone(fg, sub))
    }
}

/** 虚线边（「其他…」、打标签的「＋」）。[size] 是圆角半径 */
fun Modifier.dashedBorder(color: Color, size: Dp): Modifier = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(this.size.width - w, this.size.height - w),
        cornerRadius = CornerRadius(size.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    )
}

/**
 * 排成一行，放不下的就不画（不折行、不截断）：标签多了只露最前面那几个，其余靠打字找。
 * 孩子按给的顺序摆，第一个放不下的起后面的全不画
 */
@Composable
fun OneLineRow(modifier: Modifier = Modifier, gap: Dp = 6.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val space = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        var used = 0
        val shown = placeables.takeWhile { p ->
            val next = if (used == 0) p.width else used + space + p.width
            (next <= constraints.maxWidth).also { if (it) used = next }
        }
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(used.coerceAtLeast(constraints.minWidth), height.coerceAtLeast(constraints.minHeight)) {
            var x = 0
            shown.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + space
            }
        }
    }
}
