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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 细线图标，手绘复刻视觉稿里的 20x20 viewBox SVG（见 DESIGN.md §8.1「图标一律细线，不要填充」）。
 * 稿子里线宽是 1.1，落到 20dp 的图标上约等于 1.1dp —— 别加粗，粗了就不是墨线是记号笔了。
 * 没有引入 material-icons-extended：那个库不小，这几个图标手画成本更低。
 */
private fun strokeOf(strokeWidthPx: Float) =
    Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun MicIcon(modifier: Modifier = Modifier, size: Dp = 19.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        val stroke = strokeOf(sw)
        drawRoundRect(
            color = tint,
            topLeft = Offset(7.4f * k, 2.6f * k),
            size = Size(5.2f * k, 9.4f * k),
            cornerRadius = CornerRadius(2.6f * k, 2.6f * k),
            style = stroke
        )
        val arc = Path().apply {
            moveTo(4.4f * k, 9.4f * k)
            cubicTo(4.4f * k, 12.5f * k, 6.9f * k, 15f * k, 10f * k, 15f * k)
            cubicTo(13.1f * k, 15f * k, 15.6f * k, 12.5f * k, 15.6f * k, 9.4f * k)
        }
        drawPath(arc, tint, style = stroke)
        drawLine(tint, Offset(10 * k, 15 * k), Offset(10 * k, 17.4f * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** 发送 —— 一支向上的箭头，不是纸飞机 */
@Composable
fun SendIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color, strokeWidth: Dp = 1.4.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(10 * k, 15.6f * k), Offset(10 * k, 4.6f * k), strokeWidth = sw, cap = StrokeCap.Round)
        val chevron = Path().apply {
            moveTo(5.4f * k, 9.2f * k)
            lineTo(10 * k, 4.4f * k)
            lineTo(14.6f * k, 9.2f * k)
        }
        drawPath(chevron, tint, style = strokeOf(sw))
    }
}

/** 顶栏左边那个 —— 三条横线，第三条短一截 */
@Composable
fun MenuIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(3 * k, 5.5f * k), Offset(17 * k, 5.5f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(3 * k, 10 * k), Offset(17 * k, 10 * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(3 * k, 14.5f * k), Offset(12 * k, 14.5f * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** 设置 —— 一颗带光芒的小圆，不是齿轮 */
@Composable
fun SettingsIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawCircle(tint, radius = 2.6f * k, center = Offset(10 * k, 10 * k), style = strokeOf(sw))
        // 四正 + 四斜，共八道光芒
        val rays = listOf(
            Offset(10f, 2.6f) to Offset(10f, 4.8f),
            Offset(10f, 15.2f) to Offset(10f, 17.4f),
            Offset(17.4f, 10f) to Offset(15.2f, 10f),
            Offset(5.2f, 10f) to Offset(2.6f, 10f),
            Offset(15.2f, 4.8f) to Offset(13.6f, 6.4f),
            Offset(6.4f, 13.6f) to Offset(4.8f, 15.2f),
            Offset(15.2f, 15.2f) to Offset(13.6f, 13.6f),
            Offset(6.4f, 6.4f) to Offset(4.8f, 4.8f)
        )
        rays.forEach { (a, b) ->
            drawLine(
                tint, Offset(a.x * k, a.y * k), Offset(b.x * k, b.y * k),
                strokeWidth = sw, cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun ChevronRightIcon(modifier: Modifier = Modifier, size: Dp = 14.dp, tint: Color, strokeWidth: Dp = 1.4.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val path = Path().apply {
            moveTo(7.6f * k, 4.6f * k)
            lineTo(12.8f * k, 10f * k)
            lineTo(7.6f * k, 15.4f * k)
        }
        drawPath(path, tint, style = strokeOf(strokeWidth.toPx()))
    }
}

/** 「已记下」前面那个朱砂对勾 */
@Composable
fun CheckIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.5.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val path = Path().apply {
            moveTo(2.4f * k, 6.3f * k)
            lineTo(4.9f * k, 8.8f * k)
            lineTo(9.6f * k, 3.4f * k)
        }
        drawPath(path, tint, style = strokeOf(strokeWidth.toPx()))
    }
}

/** 重复徽标里的井字格 */
@Composable
fun RepeatIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(2 * k, 4.2f * k), Offset(10 * k, 4.2f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(2 * k, 7.8f * k), Offset(10 * k, 7.8f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(4.4f * k, 2 * k), Offset(4.4f * k, 10.2f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(7.6f * k, 2 * k), Offset(7.6f * k, 10.2f * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun BackIcon(modifier: Modifier = Modifier, size: Dp = 19.dp, tint: Color, strokeWidth: Dp = 1.3.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val path = Path().apply {
            moveTo(12.4f * k, 4.6f * k)
            lineTo(7.2f * k, 10f * k)
            lineTo(12.4f * k, 15.4f * k)
        }
        drawPath(path, tint, style = strokeOf(strokeWidth.toPx()))
    }
}

@Composable
fun PlusIcon(modifier: Modifier = Modifier, size: Dp = 17.dp, tint: Color, strokeWidth: Dp = 1.4.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(10 * k, 3.6f * k), Offset(10 * k, 16.4f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(3.6f * k, 10 * k), Offset(16.4f * k, 10 * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun TrashIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(3.4f * k, 5.8f * k), Offset(16.6f * k, 5.8f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(7.6f * k, 3.4f * k), Offset(12.4f * k, 3.4f * k), strokeWidth = sw, cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(5 * k, 5.8f * k)
            lineTo(5.9f * k, 16.6f * k)
            lineTo(14.1f * k, 16.6f * k)
            lineTo(15 * k, 5.8f * k)
        }
        drawPath(body, tint, style = strokeOf(sw))
        drawLine(tint, Offset(8.3f * k, 8.6f * k), Offset(8.7f * k, 13.8f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(11.7f * k, 8.6f * k), Offset(11.3f * k, 13.8f * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
