package com.abc.daodian.shared.ui

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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 细线图标，手绘复刻视觉稿里的 20x20 viewBox SVG（见 DESIGN.md §8.1「图标一律细线、不填充」）。
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

/** 键盘 —— 圆角方框里一排键、一根空格。对话页「按住说话」时点它换回打字 */
@Composable
fun KeyboardIcon(modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color, strokeWidth: Dp = 1.2.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(2.4f * k, 4.8f * k),
            size = Size(15.2f * k, 10.4f * k),
            cornerRadius = CornerRadius(2.2f * k, 2.2f * k),
            style = strokeOf(sw)
        )
        for (x in floatArrayOf(5.85f, 8.55f, 11.45f, 14.15f)) {
            drawCircle(tint, radius = sw * 0.6f, center = Offset(x * k, 8.2f * k))
        }
        drawLine(tint, Offset(7.2f * k, 11.8f * k), Offset(12.8f * k, 11.8f * k), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** 两道向上的折线 ——「往上滑」。画在 18x14 的格子里，[width] 是宽 */
@Composable
fun ChevronsUpIcon(modifier: Modifier = Modifier, width: Dp = 18.dp, tint: Color, strokeWidth: Dp = 1.4.dp) {
    Canvas(modifier.size(width = width, height = width * 14 / 18)) {
        val k = this.size.width / 18f
        val stroke = strokeOf(strokeWidth.toPx())
        for (top in floatArrayOf(2f, 7.5f)) {
            val path = Path().apply {
                moveTo(4f * k, (top + 4f) * k)
                lineTo(9f * k, top * k)
                lineTo(14f * k, (top + 4f) * k)
            }
            drawPath(path, tint, style = stroke)
        }
    }
}

/** 叉 */
@Composable
fun CloseIcon(modifier: Modifier = Modifier, size: Dp = 12.dp, tint: Color, strokeWidth: Dp = 1.4.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(4.5f * k, 4.5f * k), Offset(15.5f * k, 15.5f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(15.5f * k, 4.5f * k), Offset(4.5f * k, 15.5f * k), strokeWidth = sw, cap = StrokeCap.Round)
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

/** 搜索框里的放大镜 */
@Composable
fun SearchIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color, strokeWidth: Dp = 1.3.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        drawCircle(tint, radius = 5.6f * k, center = Offset(8.8f * k, 8.8f * k), style = strokeOf(sw))
        drawLine(tint, Offset(13f * k, 13f * k), Offset(16.6f * k, 16.6f * k), strokeWidth = sw, cap = StrokeCap.Round)
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

/** 重复：一圈带箭头的弧（↻），右边留个口，箭头顺时针 */
@Composable
fun RepeatIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val r = 4.3f * k
        val c = Offset(6f * k, 6.3f * k)
        val start = 50f
        val sweep = 290f
        drawArc(
            tint, startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = strokeOf(strokeWidth.toPx())
        )
        // 箭头在弧的尾巴上，朝顺时针方向
        val end = Math.toRadians((start + sweep).toDouble())
        val tip = Offset(c.x + r * cos(end).toFloat(), c.y + r * sin(end).toFloat())
        val dir = atan2(cos(end), -sin(end))
        val len = 3.2f * k
        val head = Path().apply {
            listOf(dir + Math.toRadians(150.0), dir - Math.toRadians(150.0)).forEachIndexed { i, a ->
                val p = Offset(tip.x + len * cos(a).toFloat(), tip.y + len * sin(a).toFloat())
                if (i == 0) { moveTo(p.x, p.y); lineTo(tip.x, tip.y) } else lineTo(p.x, p.y)
            }
        }
        drawPath(head, tint, style = strokeOf(strokeWidth.toPx()))
    }
}

/** 轻提醒：一片叶子，中间一道叶脉 */
@Composable
fun LeafIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val leaf = Path().apply {
            moveTo(2.6f * k, 9.4f * k)
            cubicTo(1.8f * k, 5.2f * k, 5.2f * k, 2.2f * k, 10.4f * k, 1.6f * k)
            cubicTo(10.2f * k, 6.8f * k, 7.2f * k, 10.2f * k, 2.6f * k, 9.4f * k)
            close()
        }
        val sw = strokeOf(strokeWidth.toPx())
        drawPath(leaf, tint, style = sw)
        drawLine(tint, Offset(1.4f * k, 10.6f * k), Offset(7.6f * k, 4.4f * k), strokeWidth = strokeWidth.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * 清单的方框（§4.5）：空框；[checked] 时填满 [tint]，里面一道 [mark] 色的勾。
 * 编辑页一行前面那个（16dp），提醒页小字里那个（11dp）
 */
@Composable
fun BoxIcon(
    modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, checked: Boolean = false,
    mark: Color = Color.Transparent, dashed: Boolean = false, strokeWidth: Dp = 1.1.dp
) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val sw = strokeWidth.toPx()
        val r = CornerRadius(2f * k)
        val inset = 1f * k
        val box = Size(10f * k, 10f * k)
        if (checked) {
            drawRoundRect(tint, topLeft = Offset(inset, inset), size = box, cornerRadius = r)
            drawPath(
                Path().apply { moveTo(3.6f * k, 6.2f * k); lineTo(5.3f * k, 7.9f * k); lineTo(8.5f * k, 4.4f * k) },
                mark, style = strokeOf(sw * 1.2f)
            )
        } else {
            drawRoundRect(
                tint, topLeft = Offset(inset, inset), size = box, cornerRadius = r,
                style = Stroke(width = sw, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2f * k, 1.6f * k)) else null)
            )
        }
    }
}

/** 一天几次：一个钟面，和「重复」那一圈分开 —— 重复只管哪几天，钟面管一天里几个钟点 */
@Composable
fun BeatIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val sw = strokeWidth.toPx()
        drawCircle(tint, radius = 4.6f * k, center = Offset(6f * k, 6f * k), style = strokeOf(sw))
        drawPath(Path().apply { moveTo(6f * k, 3.6f * k); lineTo(6f * k, 6f * k); lineTo(7.9f * k, 6f * k) }, tint, style = strokeOf(sw))
    }
}

/** 铃身 + 铃舌，[BellIcon]、[BellOffIcon] 共用 */
private fun bellPath(k: Float) = Path().apply {
    moveTo(3.4f * k, 8.4f * k)
    lineTo(3.4f * k, 5.8f * k)
    cubicTo(3.4f * k, 2.35f * k, 8.6f * k, 2.35f * k, 8.6f * k, 5.8f * k)
    lineTo(8.6f * k, 8.4f * k)
    lineTo(9.4f * k, 9.3f * k)
    lineTo(2.6f * k, 9.3f * k)
    close()
    moveTo(5.1f * k, 10.6f * k)
    quadraticTo(6f * k, 11.6f * k, 6.9f * k, 10.6f * k)
}

/** 再提：一只铃，两边两道响声 —— 还会再响 */
@Composable
fun BellIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val sw = strokeOf(strokeWidth.toPx())
        drawPath(bellPath(k), tint, style = sw)
        drawPath(Path().apply { moveTo(1.2f * k, 4.2f * k); quadraticTo(1.5f * k, 2.9f * k, 2.4f * k, 2.2f * k) }, tint, style = sw)
        drawPath(Path().apply { moveTo(10.8f * k, 4.2f * k); quadraticTo(10.5f * k, 2.9f * k, 9.6f * k, 2.2f * k) }, tint, style = sw)
    }
}

/** 没送到：一只铃，斜着划一道 */
@Composable
fun BellOffIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        drawPath(bellPath(k), tint, style = strokeOf(strokeWidth.toPx()))
        drawLine(tint, Offset(1.6f * k, 1.6f * k), Offset(10.4f * k, 10.4f * k), strokeWidth = strokeWidth.toPx(), cap = StrokeCap.Round)
    }
}

/** 提前提醒：沙漏 —— 日子快到了，先提一下 */
@Composable
fun HourglassIcon(modifier: Modifier = Modifier, size: Dp = 11.dp, tint: Color, strokeWidth: Dp = 1.1.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 12f
        val sw = strokeWidth.toPx()
        drawLine(tint, Offset(3f * k, 1.4f * k), Offset(9f * k, 1.4f * k), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(3f * k, 10.6f * k), Offset(9f * k, 10.6f * k), strokeWidth = sw, cap = StrokeCap.Round)
        val glass = Path().apply {
            moveTo(3.8f * k, 1.4f * k)
            cubicTo(3.8f * k, 4.4f * k, 6f * k, 4.9f * k, 6f * k, 6f * k)
            cubicTo(6f * k, 7.1f * k, 3.8f * k, 7.6f * k, 3.8f * k, 10.6f * k)
            moveTo(8.2f * k, 1.4f * k)
            cubicTo(8.2f * k, 4.4f * k, 6f * k, 4.9f * k, 6f * k, 6f * k)
            cubicTo(6f * k, 7.1f * k, 8.2f * k, 7.6f * k, 8.2f * k, 10.6f * k)
        }
        drawPath(glass, tint, style = strokeOf(sw))
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

/**
 * 「停」。这一个是实心的 —— 图标一律细线那条规矩在这儿要让路：
 * 空心方块在空心圆圈里，两层描边套着看不出是个能按的东西。
 */
@Composable
fun StopIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        drawRoundRect(
            color = tint,
            topLeft = Offset(6.4f * k, 6.4f * k),
            size = Size(7.2f * k, 7.2f * k),
            cornerRadius = CornerRadius(1.4f * k, 1.4f * k)
        )
    }
}

/** 相册 —— 一张带山和太阳的画片。对话页加图片的底纸上用 */
@Composable
fun GalleryIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color, strokeWidth: Dp = 1.2.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val sw = strokeWidth.toPx()
        val stroke = strokeOf(sw)
        drawRoundRect(
            color = tint,
            topLeft = Offset(2.6f * k, 4f * k),
            size = Size(14.8f * k, 12f * k),
            cornerRadius = CornerRadius(2f * k, 2f * k),
            style = stroke
        )
        val hills = Path().apply {
            moveTo(2.8f * k, 13.4f * k)
            lineTo(7f * k, 9.6f * k)
            lineTo(10.4f * k, 12.6f * k)
            lineTo(13f * k, 10.4f * k)
            lineTo(17.2f * k, 14f * k)
        }
        drawPath(hills, tint, style = stroke)
        drawCircle(tint, radius = 1.1f * k, center = Offset(13.4f * k, 7.6f * k), style = stroke)
    }
}

/** 相机 */
@Composable
fun CameraIcon(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color, strokeWidth: Dp = 1.2.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 20f
        val stroke = strokeOf(strokeWidth.toPx())
        val body = Path().apply {
            moveTo(3f * k, 7.2f * k)
            cubicTo(3f * k, 6.3f * k, 3.7f * k, 5.6f * k, 4.6f * k, 5.6f * k)
            lineTo(6.6f * k, 5.6f * k)
            lineTo(7.8f * k, 4f * k)
            lineTo(12.2f * k, 4f * k)
            lineTo(13.4f * k, 5.6f * k)
            lineTo(15.4f * k, 5.6f * k)
            cubicTo(16.3f * k, 5.6f * k, 17f * k, 6.3f * k, 17f * k, 7.2f * k)
            lineTo(17f * k, 14.4f * k)
            cubicTo(17f * k, 15.3f * k, 16.3f * k, 16f * k, 15.4f * k, 16f * k)
            lineTo(4.6f * k, 16f * k)
            cubicTo(3.7f * k, 16f * k, 3f * k, 15.3f * k, 3f * k, 14.4f * k)
            close()
        }
        drawPath(body, tint, style = stroke)
        drawCircle(tint, radius = 3f * k, center = Offset(10f * k, 10.6f * k), style = stroke)
    }
}
