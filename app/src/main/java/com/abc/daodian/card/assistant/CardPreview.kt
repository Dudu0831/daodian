package com.abc.daodian.card.assistant

import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.abc.daodian.card.Cards
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import kotlinx.coroutines.launch

/**
 * 对话里那张卡片的预览（设计稿「在对话里设计、预览、放上桌面」）：就是桌面上那份 RemoteViews，原样铺进来，
 * 不另画一张 —— 预览和桌面不会对不上。按钮在这里点不了。底下一行「放到桌面 ›」，点了直接请桌面加，不用再跟模型说一句。
 */
@Composable
internal fun CardPreview(cardId: Long, raw: String) {
    val context = LocalContext.current
    val colors = DaodianColors.current
    val scope = rememberCoroutineScope()
    val size = remember(raw) { CardSpecs.read(raw)?.size ?: CardSize.S2X2 }
    val views by produceState<RemoteViews?>(null, raw) { value = Cards.preview(context, raw) }
    val placed by produceState(false, cardId) { value = Cards.byId(context, cardId)?.widgetId != null }
    var note by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().background(colors.surfaceAlt, RoundedCornerShape(18.dp)).padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // 4 格宽的放不下就按可用宽度来，高度照桌面上的比例
            val w = minOf(size.widthDp.dp, maxWidth)
            val h = if (size.widthDp.dp <= maxWidth) size.heightDp.dp else size.heightDp.dp * (maxWidth / size.widthDp.dp)
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame ->
                    frame.removeAllViews()
                    views?.let { v -> runCatching { frame.addView(v.apply(frame.context, frame)) } }
                },
                modifier = Modifier.size(w, h)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${size.label} · 预览", style = DaodianType.caption.copy(fontSize = 11.5.sp), color = colors.muted)
            val label = note ?: if (placed) "在桌面上" else "放到桌面 ›"
            Text(
                label,
                style = DaodianType.caption.copy(fontSize = 11.5.sp),
                color = if (note == null && !placed) colors.ink else colors.muted,
                modifier = Modifier.heightIn(min = 40.dp).padding(vertical = 12.dp).let { m ->
                    if (note == null && !placed) m.clickable {
                        scope.launch {
                            val p = Cards.pin(context, cardId)
                            note = when {
                                p == null -> "这张不在了"
                                p.already -> "在桌面上"
                                p.asked -> "桌面弹了框，点「添加」"
                                else -> "长按桌面 → 小组件 → 到点「卡片 ${size.label}」"
                            }
                        }
                    } else m
                }
            )
        }
    }
}
