package com.abc.daodian.assistant.conversation

import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abc.daodian.assistant.conversation.data.ConversationImageFiles
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 随话发的图片在对话里怎么画：气泡上面那一块、点开全屏看。见 DESIGN.md §6.10
 * 输入条上还没发的那一行在 ImageAttach.kt。
 */

/** 和文字气泡一样宽 */
private val BubbleWidth = 264.dp

/** 只发一张时按原来的比例画，最宽、最高到这儿；再扁、再长的只露中间一段 */
private val SingleMaxWidth = 200.dp
private val SingleMaxHeight = 220.dp
private val SingleMinWidth = 100.dp
private val SingleMinHeight = 72.dp

private val ImageGap = 4.dp
private val ImageShape = RoundedCornerShape(14.dp)

/** 底下没有文字气泡时，那只小尾巴角长在图上 */
private val ImageShapeTailed = RoundedCornerShape(14.dp, 14.dp, 5.dp, 14.dp)

private const val MAX_ZOOM = 5f

/** 解好的图留一份在内存里，列表滚回来不用重解。按字节算，大约 32MB */
private val decoded = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/** [path] 那张图，解到长边不小于 [minPx]。还没解出来、文件没了都是 null */
@Composable
private fun rememberChatImage(path: String, minPx: Int): ImageBitmap? {
    val key = "$path@$minPx"
    val image by produceState<ImageBitmap?>(decoded.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { ConversationImageFiles.decode(path, minPx)?.asImageBitmap() }?.also { decoded.put(key, it) }
        }
    }
    return image
}

/** 一张图。解出来之前（和文件没了的时候）先是一块 [backdrop] */
@Composable
internal fun ConversationImage(
    path: String,
    minPx: Int,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop,
    backdrop: Color = DaodianColors.current.surfaceAlt
) {
    val image = rememberChatImage(path, minPx)
    Box(modifier.background(backdrop)) {
        if (image != null) Image(image, contentDescription = "图片", contentScale = scale, modifier = Modifier.matchParentSize())
    }
}

/**
 * 气泡上面的图。一张按原比例；两到四张排成一行方格，合起来和气泡一样宽。
 * 占多大从文件名里的宽高算，不等图解出来 —— 不然图一到，下面的内容会被顶一下。
 * [tail]：底下没有文字气泡，尾巴角长在最后一张图上
 */
@Composable
internal fun BubbleImages(images: List<String>, tail: Boolean, onOpen: (Int) -> Unit) {
    val colors = DaodianColors.current
    val density = LocalDensity.current
    if (images.size == 1) {
        val path = images[0]
        val aspect = remember(path) { ConversationImageFiles.aspect(path) }
        val (w, h) = if (aspect >= SingleMaxWidth / SingleMaxHeight) {
            SingleMaxWidth to (SingleMaxWidth / aspect).coerceAtLeast(SingleMinHeight)
        } else {
            (SingleMaxHeight * aspect).coerceAtLeast(SingleMinWidth) to SingleMaxHeight
        }
        val shape = if (tail) ImageShapeTailed else ImageShape
        ConversationImage(
            path,
            minPx = with(density) { SingleMaxHeight.roundToPx() },
            modifier = Modifier.size(w, h).clip(shape).border(1.dp, colors.rule, shape).clickable { onOpen(0) }
        )
    } else {
        val side = (BubbleWidth - ImageGap * (images.size - 1)) / images.size
        Row(horizontalArrangement = Arrangement.spacedBy(ImageGap)) {
            images.forEachIndexed { i, path ->
                val shape = if (tail && i == images.lastIndex) ImageShapeTailed else ImageShape
                ConversationImage(
                    path,
                    minPx = with(density) { side.roundToPx() },
                    modifier = Modifier.size(side).clip(shape).border(1.dp, colors.rule, shape).clickable { onOpen(i) }
                )
            }
        }
    }
}

/**
 * 全屏看，和点开模型画的图（[SvgBlock]）是同一个样子：两指缩放、拖动，双击放大 / 还原，点一下收起。
 * 一次发了几张的左右滑；放大着的时候横着拖是挪图，不翻页
 */
@Composable
internal fun ImageViewer(images: List<String>, start: Int, onClose: () -> Unit) {
    val colors = DaodianColors.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(colors.paper)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(images[page], active = pager.currentPage == page, onZoomed = { zoomed = it }, onClose = onClose)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", style = DaodianType.caption, color = colors.muted)
                Text("两指放大 · 双击放大或还原 · 点一下收起", style = DaodianType.caption, color = colors.hint)
            }
        }
    }
}

// transformable 带 canPan 的那个重载还标着实验性
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(path: String, active: Boolean, onZoomed: (Boolean) -> Unit, onClose: () -> Unit) {
    val colors = DaodianColors.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    /** 挪图不挪出边：放大了多少，就最多露出多少 */
    fun bounded(offset: Offset, at: Float): Offset {
        val x = box.width * (at - 1f) / 2f
        val y = box.height * (at - 1f) / 2f
        return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
    }

    // 翻到别的页去了：这一张还原，回来时不是放大着的
    LaunchedEffect(active) {
        if (!active) {
            zoom = 1f
            pan = Offset.Zero
        }
    }
    LaunchedEffect(active, zoom > 1f) { if (active) onZoomed(zoom > 1f) }

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        zoom = next
        pan = if (next <= 1f) Offset.Zero else bounded(pan + panChange, next)
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (zoom > 1f) {
                            zoom = 1f
                            pan = Offset.Zero
                        } else {
                            zoom = 2.5f
                        }
                    },
                    onTap = { onClose() }
                )
            }
            // 没放大时单指的拖动不接，留给翻页
            .transformable(transform, canPan = { zoom > 1f })
    ) {
        ConversationImage(
            path,
            minPx = Int.MAX_VALUE,
            scale = ContentScale.Fit,
            backdrop = colors.paper,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            }
        )
    }
}
