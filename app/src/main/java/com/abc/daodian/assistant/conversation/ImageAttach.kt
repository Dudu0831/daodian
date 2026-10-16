package com.abc.daodian.assistant.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.abc.daodian.assistant.conversation.data.ConversationImageFiles
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.CameraIcon
import com.abc.daodian.shared.ui.CloseIcon
import com.abc.daodian.shared.ui.GalleryIcon
import com.abc.daodian.shared.ui.PlusIcon

/*
 * 加图片：输入条上选好还没发的那一行、点 ＋ 从底下上来的那张纸。见 DESIGN.md §6.10
 * 发出去之后在对话里怎么画在 ImageBlock.kt。
 */

private val ThumbSize = 60.dp
private val ThumbShape = RoundedCornerShape(12.dp)

/** 叉露在缩略图角外面这么多 */
private val BadgeOut = 6.dp

/**
 * 选好、还没发的图，排在输入框上面一行。角上的叉去掉这一张；
 * 没满的时候后面跟一格虚线 ＋（[canAdd]）—— 有图之后最右那一格是发送，要再加只能点它
 */
@Composable
internal fun PendingImages(
    images: List<String>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = DaodianColors.current
    val px = with(LocalDensity.current) { ThumbSize.roundToPx() }
    // 每一格都把叉露出去的那一圈算在自己里面（出了格子的地方点不到），所以格子之间只再隔 4dp，看着是 10dp
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp - BadgeOut)) {
        images.forEach { path ->
            key(path) {
                Box(Modifier.size(ThumbSize + BadgeOut)) {
                    ConversationImage(
                        path,
                        minPx = px,
                        modifier = Modifier.align(Alignment.BottomStart).size(ThumbSize).clip(ThumbShape).border(1.dp, colors.rule, ThumbShape)
                    )
                    // 看得见的是 20dp 的一个圈，能点的范围给大一圈
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(30.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRemove(path) },
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Box(
                            Modifier.size(20.dp).background(colors.surface, CircleShape).border(1.dp, colors.rule2, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            CloseIcon(size = 8.dp, tint = colors.ink2, strokeWidth = 1.3.dp)
                        }
                    }
                }
            }
        }
        if (canAdd) {
            Box(
                Modifier
                    .padding(top = BadgeOut)
                    .size(ThumbSize)
                    .clip(ThumbShape)
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawRoundRect(
                            color = colors.rule2,
                            topLeft = Offset(w / 2, w / 2),
                            size = Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                        )
                    }
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                PlusIcon(tint = colors.muted)
            }
        }
    }
}

/**
 * 点 ＋ 从最下面上来的纸：相册、相机。和编辑页「几点」那张是同一种纸。
 * 两样都是系统的界面 —— 相册走系统的选图页，不用给相册权限；相机调系统相机，不用相机权限。
 * [room]：这一句还能再加几张
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageSourceSheet(room: Int, onGallery: () -> Unit, onCamera: () -> Unit, onDismiss: () -> Unit) {
    val colors = DaodianColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        scrimColor = colors.ink.copy(alpha = 0.32f),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 18.dp).size(36.dp, 4.dp)
                    .background(colors.rule, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Text("加图片", style = DaodianType.sectionLabel, color = colors.muted, modifier = Modifier.padding(bottom = 6.dp))
            SourceRow(
                title = "相册",
                note = if (room >= ConversationImageFiles.MAX_PER_MESSAGE) "从相册里选，一次最多 ${ConversationImageFiles.MAX_PER_MESSAGE} 张" else "从相册里选，还能加 $room 张",
                icon = { GalleryIcon(tint = colors.ink2) },
                // 纸先收掉再去选：系统的页面盖上来，回来时它不该还摊在那儿
                onClick = {
                    onDismiss()
                    onGallery()
                }
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ruleSoft))
            SourceRow(
                title = "相机",
                note = "现在拍一张",
                icon = { CameraIcon(tint = colors.ink2) },
                onClick = {
                    onDismiss()
                    onCamera()
                }
            )
        }
    }
}

@Composable
private fun SourceRow(title: String, note: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val colors = DaodianColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        icon()
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = DaodianType.rowTitle, color = colors.ink)
            Text(note, style = DaodianType.caption, color = colors.muted)
        }
    }
}
