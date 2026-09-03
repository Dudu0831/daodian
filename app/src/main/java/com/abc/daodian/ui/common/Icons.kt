package com.abc.daodian.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 细线图标，手绘复刻视觉稿里的 24x24 viewBox SVG。见 DESIGN.md §01「图标一律细线，不要填充」。
 * 没有引入 material-icons-extended —— 那个库不小，这几个图标手画成本更低。
 */
private fun strokeOf(strokeWidthPx: Float) =
    Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun MicIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color, strokeWidth: Dp = 1.6.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val sw = strokeWidth.toPx()
        val stroke = strokeOf(sw)
        // 麦克风头（胶囊）
        drawRoundRect(
            color = tint,
            topLeft = Offset(9 * k, 2 * k),
            size = Size(6 * k, 12 * k),
            cornerRadius = CornerRadius(3 * k, 3 * k),
            style = stroke
        )
        // 底座弧线
        val arc = Path().apply {
            moveTo(5 * k, 11 * k)
            cubicTo(5 * k, 15 * k, 8.13f * k, 18 * k, 12 * k, 18 * k)
            cubicTo(15.87f * k, 18 * k, 19 * k, 15 * k, 19 * k, 11 * k)
        }
        drawPath(arc, tint, style = stroke)
        drawLine(tint, Offset(12 * k, 18 * k), Offset(12 * k, 22 * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(8 * k, 22 * k), Offset(16 * k, 22 * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun SendIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color, strokeWidth: Dp = 1.9.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(12 * k, 19 * k), Offset(12 * k, 5 * k), strokeWidth = sw, cap = StrokeCap.Round)
        val chevron = Path().apply {
            moveTo(6 * k, 11 * k)
            lineTo(12 * k, 5 * k)
            lineTo(18 * k, 11 * k)
        }
        drawPath(chevron, tint, style = strokeOf(sw))
    }
}

@Composable
fun ListIcon(modifier: Modifier = Modifier, size: Dp = 19.dp, tint: Color, strokeWidth: Dp = 1.6.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val sw = strokeWidth.toPx()
        listOf(6f, 12f, 18f).forEach { y ->
            drawLine(tint, Offset(9 * k, y * k), Offset(20 * k, y * k), strokeWidth = sw, cap = StrokeCap.Round)
        }
        listOf(6f, 12f, 18f).forEach { y ->
            val tick = Path().apply {
                moveTo(4 * k, y * k)
                lineTo(5 * k, (y + 1) * k)
                lineTo(7 * k, (y - 1.5f) * k)
            }
            drawPath(tick, tint, style = strokeOf(sw))
        }
    }
}

@Composable
fun SettingsIcon(modifier: Modifier = Modifier, size: Dp = 19.dp, tint: Color, strokeWidth: Dp = 1.6.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(4 * k, 7 * k), Offset(20 * k, 7 * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.2f * k, center = Offset(14 * k, 7 * k), style = strokeOf(sw))
        drawLine(tint, Offset(4 * k, 17 * k), Offset(20 * k, 17 * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.2f * k, center = Offset(10 * k, 17 * k), style = strokeOf(sw))
    }
}

@Composable
fun ChevronRightIcon(modifier: Modifier = Modifier, size: Dp = 15.dp, tint: Color, strokeWidth: Dp = 1.8.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val path = Path().apply {
            moveTo(9 * k, 6 * k)
            lineTo(15 * k, 12 * k)
            lineTo(9 * k, 18 * k)
        }
        drawPath(path, tint, style = strokeOf(strokeWidth.toPx()))
    }
}

@Composable
fun CheckIcon(modifier: Modifier = Modifier, size: Dp = 14.dp, tint: Color, strokeWidth: Dp = 2.2.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val path = Path().apply {
            moveTo(4 * k, 12 * k)
            lineTo(9 * k, 17 * k)
            lineTo(20 * k, 6 * k)
        }
        drawPath(path, tint, style = strokeOf(strokeWidth.toPx()))
    }
}

@Composable
fun BackIcon(modifier: Modifier = Modifier, size: Dp = 19.dp, tint: Color, strokeWidth: Dp = 1.8.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val path = Path().apply {
            moveTo(15 * k, 6 * k)
            lineTo(9 * k, 12 * k)
            lineTo(15 * k, 18 * k)
        }
        drawPath(path, tint, style = strokeOf(strokeWidth.toPx()))
    }
}

@Composable
fun PlusIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color, strokeWidth: Dp = 2.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(12 * k, 4 * k), Offset(12 * k, 20 * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(4 * k, 12 * k), Offset(20 * k, 12 * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun TrashIcon(modifier: Modifier = Modifier, size: Dp = 17.dp, tint: Color, strokeWidth: Dp = 1.6.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val sw = strokeWidth.toPx()
        val stroke = strokeOf(sw)
        drawLine(tint, Offset(4 * k, 7 * k), Offset(20 * k, 7 * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(9 * k, 4 * k), Offset(15 * k, 4 * k), strokeWidth = sw, cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(6 * k, 7 * k)
            lineTo(7 * k, 20 * k)
            lineTo(17 * k, 20 * k)
            lineTo(18 * k, 7 * k)
        }
        drawPath(body, tint, style = stroke)
        drawLine(
            tint, Offset(10 * k, 11 * k), Offset(10.5f * k, 17 * k),
            strokeWidth = sw * 0.8f, cap = StrokeCap.Round,
            pathEffect = PathEffect.cornerPathEffect(0f)
        )
        drawLine(tint, Offset(14 * k, 11 * k), Offset(13.5f * k, 17 * k), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
    }
}
