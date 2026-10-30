package com.abc.daodian.whatsnew.presentation

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.CloseIcon
import com.abc.daodian.whatsnew.Page
import com.abc.daodian.whatsnew.Release
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新功能弹窗（设计稿 claude.ai/artifact/FWfAW5h7XJbiAzfFwg8UR2）：左上「0.2.0 · 新功能」、右上叉，
 * 中间一段动图，底下标题和一句话；不止一页时左右翻，底下出页码点。只有叉和返回键收得掉，点暗处不收。
 */
@Composable
internal fun WhatsNewDialog(release: Release, onClose: () -> Unit) {
    val colors = DaodianColors.current
    val swallow = remember { MutableInteractionSource() }
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = swallow, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .clickable(interactionSource = swallow, indication = null) {}
        ) {
            Row(Modifier.padding(start = 24.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${release.versionName} · 新功能", style = DaodianType.sectionLabel, color = colors.muted)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(onClick = onClose)
                        .semantics { contentDescription = "关闭" },
                    contentAlignment = Alignment.Center
                ) {
                    CloseIcon(size = 16.dp, tint = colors.ink2, strokeWidth = 1.5.dp)
                }
            }

            val pager = rememberPagerState { release.pages.size }
            HorizontalPager(state = pager, verticalAlignment = Alignment.Top) { i -> PageBody(release.pages[i]) }

            if (release.pages.size > 1) {
                val scope = rememberCoroutineScope()
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(release.pages.size) { i ->
                        val on = i == pager.currentPage
                        Box(
                            Modifier
                                .size(28.dp)
                                .clickable { scope.launch { pager.animateScrollToPage(i) } }
                                .semantics { contentDescription = "第 ${i + 1} 页" },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(width = if (on) 16.dp else 6.dp, height = 6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (on) colors.accent else colors.rule2)
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

@Composable
private fun PageBody(page: Page) {
    val colors = DaodianColors.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 2.dp)
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceAlt),
            contentAlignment = Alignment.Center
        ) {
            page.media?.let { AssetAnimation(it, Modifier.fillMaxSize()) }
        }
        Text(
            page.title,
            style = DaodianType.screenTitle.copy(fontWeight = FontWeight.Medium, lineHeight = 28.sp, letterSpacing = 0.sp),
            color = colors.ink,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)
        )
        Text(
            page.line,
            style = DaodianType.body,
            color = colors.ink2,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)
        )
    }
}

/** assets 里的一张动态 WebP，一直循环。读不出来（文件不在、坏了）就什么都不画，留着那块底色 */
@Composable
private fun AssetAnimation(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, path)) }.getOrNull()
        }
    }
    val d = drawable ?: return
    DisposableEffect(d) {
        (d as? AnimatedImageDrawable)?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            start()
        }
        onDispose { (d as? AnimatedImageDrawable)?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageDrawable(d) },
        modifier = modifier
    )
}
