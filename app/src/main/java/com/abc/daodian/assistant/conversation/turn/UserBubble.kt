package com.abc.daodian.assistant.conversation.turn

import com.abc.daodian.assistant.conversation.ConversationItem
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion

/** 刚发出的气泡从下方升起；列表滚回来重组时不再升。[onOpenImage]：点了第几张图 */
@Composable
fun UserBubble(msg: ConversationItem.UserText, onOpenImage: (Int) -> Unit = {}) =
    UserBubble(msg.text, msg.sentAt, msg.images, onOpenImage, msg.audio)

/**
 * 随话发的图在上、字在下，都靠右，一起升起。只有图的那句没有墨色气泡（DESIGN.md §6.10）
 */
@Composable
fun UserBubble(
    text: String,
    sentAt: Long,
    images: List<String> = emptyList(),
    onOpenImage: (Int) -> Unit = {},
    /** 语音条的原声：有它就画成语音条，字收着、长按才出来（[VoiceBubble]） */
    audio: String? = null
) {
    val colors = DaodianColors.current
    val rise = remember { Animatable(if (System.currentTimeMillis() - sentAt < 600) 0f else 1f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, Motion.settle()) }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = rise.value
                translationY = (1f - p) * 26.dp.toPx()
                alpha = 0.15f + 0.85f * p
                scaleX = 0.97f + 0.03f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(1f, 1f)
            },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (images.isNotEmpty()) BubbleImages(images, tail = text.isBlank() && audio == null, onOpen = onOpenImage)
        if (audio != null) {
            VoiceBubble(audio, text)
        } else if (text.isNotBlank()) {
            Text(
                text,
                style = DaodianType.body,
                color = colors.onSolid,
                modifier = Modifier
                    .widthIn(max = 264.dp)
                    .background(colors.solid, RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}
