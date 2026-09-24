package com.abc.daodian.agent.conversation

import android.graphics.Picture
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianPalette
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.DarkPalette
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.min

/*
 * 模型画的图（```svg 代码块，见 Markdown.kt 的 [MdBlock.Svg]）。见 DESIGN.md §6.11
 *
 * 用 AndroidSVG 解析、录成 Picture（矢量，放大不糊），只画静态的 —— 没有脚本、不联网（它不带外部文件解析器，
 * <image> 引外面的东西一律不取）。还在流、没写到 </svg> 的画一个虚线占位「在画图」；画不出来的退回成代码块，原文不丢。
 * 点一下全屏看，两指放大。
 */

/** 对话里一张图最多比宽高这么多倍，再高的缩小了放进去 */
private const val MAX_TALL = 1.4f

/** 全屏最多放大到这么多倍 */
private const val MAX_ZOOM = 5f

/** 模型没写 viewBox 也没写宽高时，按这个大小画（提示词让它写 viewBox，宽 340） */
private const val DEFAULT_W = 340f
private const val DEFAULT_H = 220f

/** 解析好的一张图。[width]×[height] 是它自己的坐标（viewBox） */
private class Drawing(val picture: Picture, val width: Float, val height: Float)

private sealed interface Rendered {
    data object Pending : Rendered
    data class Ok(val drawing: Drawing) : Rendered
    data object Failed : Rendered
}

/** 滚出屏幕再滚回来、重启读回的历史，不用每次重新解析 */
private val cache = LruCache<String, Drawing>(12)

private val setup by lazy {
    // 模型写的东西，不展开 XML 实体（防「十亿笑声」那种）
    SVG.setInternalEntitiesEnabled(false)
}

/**
 * [streaming] = 这一回合还在流。没写完的图在流就画占位；流完了还没写完（模型断了），照样试着画，画不出来退回代码。
 * [fallback] 是画不出来时的代码块。
 */
@Composable
internal fun SvgBlock(b: MdBlock.Svg, modifier: Modifier, streaming: Boolean, fallback: @Composable () -> Unit) {
    val colors = DaodianColors.current
    val source = b.source
    if (!b.done && streaming) {
        Drafting(ratioGuess(source), modifier)
        return
    }
    val key = "${colors == DarkPalette}\u0000$source"
    val state by produceState<Rendered>(cache[key]?.let { Rendered.Ok(it) } ?: Rendered.Pending, key) {
        if (value is Rendered.Ok) return@produceState
        value = withContext(Dispatchers.Default) {
            drawingOf(source, colors)?.also { cache.put(key, it) }?.let { Rendered.Ok(it) } ?: Rendered.Failed
        }
    }
    when (val s = state) {
        Rendered.Pending -> Drafting(ratioGuess(source), modifier)
        Rendered.Failed -> Column(modifier) {
            Text("这张图画不出来，原文在这里：", style = DaodianType.caption, color = colors.hint)
            Spacer(Modifier.height(8.dp))
            fallback()
        }
        is Rendered.Ok -> {
            val d = s.drawing
            var open by remember { mutableStateOf(false) }
            Canvas(
                modifier
                    .fillMaxWidth()
                    .aspectRatio((d.width / d.height).coerceAtLeast(1f / MAX_TALL))
                    .clipToBounds()
                    .clickable(onClickLabel = "放大看") { open = true }
            ) { drawDrawing(d) }
            if (open) SvgViewer(d) { open = false }
        }
    }
}

/** 还在画：虚线框（和当初「起稿」一个意思）+ 墨条。框的比例照 viewBox 猜，画好了不跳 */
@Composable
private fun Drafting(ratio: Float, modifier: Modifier) {
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

/** 全屏看：两指缩放、拖动，双击放大 / 还原，点一下收起 */
@Composable
private fun SvgViewer(d: Drawing, onClose: () -> Unit) {
    val colors = DaodianColors.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(colors.paper)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, delta, gesture, _ ->
                            val next = (zoom * gesture).coerceIn(1f, MAX_ZOOM)
                            // 两指中间那一点不动
                            pan = if (next <= 1f) Offset.Zero else centroid - (centroid - pan) * (next / zoom) + delta
                            zoom = next
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { at ->
                                if (zoom > 1f) {
                                    zoom = 1f
                                    pan = Offset.Zero
                                } else {
                                    zoom = 2.5f
                                    pan = at - at * 2.5f
                                }
                            },
                            onTap = { onClose() }
                        )
                    }
            ) { drawDrawing(d, zoom, pan, margin = 0.92f) }
            Text(
                "两指放大 · 双击放大或还原 · 点一下收起",
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
                style = DaodianType.caption,
                color = colors.hint
            )
        }
    }
}

/** 等比缩放、居中画进这块地方，再叠上全屏时的缩放和平移 */
private fun DrawScope.drawDrawing(d: Drawing, zoom: Float = 1f, pan: Offset = Offset.Zero, margin: Float = 1f) {
    val fit = min(size.width / d.width, size.height / d.height) * margin
    val left = (size.width - d.width * fit) / 2
    val top = (size.height - d.height * fit) / 2
    drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        canvas.save()
        canvas.translate(pan.x, pan.y)
        canvas.scale(zoom, zoom)
        canvas.translate(left, top)
        canvas.scale(fit, fit)
        canvas.drawPicture(d.picture)
        canvas.restore()
    }
}

private fun drawingOf(source: String, colors: DaodianPalette): Drawing? = try {
    setup
    val svg = SVG.getFromString(SvgColors.themed(source, colors))
    val box = svg.documentViewBox
    val w = box?.width() ?: svg.documentWidth.takeIf { it > 0f } ?: DEFAULT_W
    val h = box?.height() ?: svg.documentHeight.takeIf { it > 0f } ?: DEFAULT_H
    if (w <= 0f || h <= 0f) {
        null
    } else {
        if (box == null) svg.setDocumentViewBox(0f, 0f, w, h)
        Drawing(svg.renderToPicture(ceil(w).toInt(), ceil(h).toInt()), w, h)
    }
} catch (e: Exception) {
    null
}

/** 占位框的比例：照 viewBox（没有就照 width / height）猜，猜不到按默认的 */
private fun ratioGuess(source: String): Float {
    val box = VIEW_BOX.find(source)
    val w = box?.groupValues?.get(1)?.toFloatOrNull() ?: WIDTH.find(source)?.groupValues?.get(1)?.toFloatOrNull()
    val h = box?.groupValues?.get(2)?.toFloatOrNull() ?: HEIGHT.find(source)?.groupValues?.get(1)?.toFloatOrNull()
    val r = if (w != null && h != null && w > 0f && h > 0f) w / h else DEFAULT_W / DEFAULT_H
    return r.coerceAtLeast(1f / MAX_TALL)
}

private val VIEW_BOX = Regex("viewBox\\s*=\\s*[\"']\\s*[-\\d.]+[\\s,]+[-\\d.]+[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)")
private val WIDTH = Regex("<svg\\b[^>]*?\\swidth\\s*=\\s*[\"']([\\d.]+)")
private val HEIGHT = Regex("<svg\\b[^>]*?\\sheight\\s*=\\s*[\"']([\\d.]+)")
