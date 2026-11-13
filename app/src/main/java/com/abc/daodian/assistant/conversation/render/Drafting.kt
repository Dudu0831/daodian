package com.abc.daodian.assistant.conversation.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.abc.daodian.assistant.conversation.render.InkWashBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

/** 占位框默认的宽高比；svg 照 viewBox 猜（[ratioGuess]） */
internal const val DRAFT_RATIO = 1.55f

/** 还在写：虚线框 + 墨条。框的比例尽量照着它将来的样子，写好了不跳 */
@Composable
internal fun Drafting(ratio: Float, modifier: Modifier) {
    val colors = DaodianColors.current
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .drawBehind {
                drawRoundRect(
                    colors.rule2,
                    cornerRadius = CornerRadius(5.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            InkWashBars(Modifier.width(120.dp))
            Spacer(Modifier.height(12.dp))
            Text("在画图", style = DaodianType.caption, color = colors.hint)
        }
    }
}

/** svg 占位框的比例：照 viewBox（没有就照 width / height）猜，猜不到按默认的 */
internal fun ratioGuess(source: String): Float {
    val box = VIEW_BOX.find(source)
    val w = box?.groupValues?.get(1)?.toFloatOrNull() ?: WIDTH.find(source)?.groupValues?.get(1)?.toFloatOrNull()
    val h = box?.groupValues?.get(2)?.toFloatOrNull() ?: HEIGHT.find(source)?.groupValues?.get(1)?.toFloatOrNull()
    val r = if (w != null && h != null && w > 0f && h > 0f) w / h else DRAFT_RATIO
    return r.coerceAtLeast(0.7f)
}

private val VIEW_BOX = Regex("viewBox\\s*=\\s*[\"']\\s*[-\\d.]+[\\s,]+[-\\d.]+[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)")
private val WIDTH = Regex("<svg\\b[^>]*?\\swidth\\s*=\\s*[\"']([\\d.]+)")
private val HEIGHT = Regex("<svg\\b[^>]*?\\sheight\\s*=\\s*[\"']([\\d.]+)")
