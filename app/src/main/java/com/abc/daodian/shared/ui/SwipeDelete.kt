package com.abc.daodian.shared.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion

/*
 * 左滑删 + 5 秒墨色「撤销」。提醒列表、记忆页共用：删都不弹确认，给撤销（DESIGN.md §08）。
 */

/** 左滑删除要拖过行宽的这个比例 */
private const val DeleteReach = 0.4f

/**
 * 左滑删一行。[rowColor] 是这行底下的纸色：往左拖时垫一层，盖住下面的「删除」
 * （提醒列表是页面的纸色，记忆页在一张纸里是 surface）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(onDelete: () -> Unit, rowColor: Color = DaodianColors.current.paper, content: @Composable () -> Unit) {
    val colors = DaodianColors.current
    // 只认距离、不认速度：Material 默认甩得够快也算，上下滑时手指往左偏一点就误删了。
    // 必须往左拖过行宽的 [DeleteReach] 才删，不够就弹回去
    lateinit var state: SwipeToDismissBoxState
    state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart && state.progress >= DeleteReach) {
                onDelete()
                true
            } else false
        },
        positionalThreshold = { total -> total * DeleteReach }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // 只在真往左滑时才画：平时画着的话，滚轮把行淡化、虚化时这层深一档的底会透出一圈方框
            if (state.dismissDirection != SwipeToDismissBoxValue.EndToStart) return@SwipeToDismissBox
            Row(
                Modifier
                    .fillMaxSize()
                    .background(colors.surfaceAlt)
                    .padding(end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrashIcon(size = 15.dp, tint = colors.red)
                Text("删除", style = DaodianType.button, color = colors.red)
            }
        }
    ) {
        // 行平时不画底：被滚轮淡化时，半透明的纸色叠在纸色上会差一点，透出一圈方框。
        // 只在往左拖的时候垫一层纸，盖住下面的「删除」
        val swiping = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
        Box(if (swiping) Modifier.background(rowColor) else Modifier) { content() }
    }
}

/** 墨色的撤销条，[label] 为 null 时收起。实心块一律是墨色（§8.1 第 1 条） */
@Composable
fun UndoBar(label: String?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    // 淡出的那几百毫秒里 label 已经是 null 了，字要留着
    var shown by remember { mutableStateOf("") }
    if (label != null) shown = label

    AnimatedVisibility(
        visible = label != null,
        enter = fadeIn(Motion.settle()) + slideInVertically(Motion.settle()) { it / 2 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 2 },
        modifier = modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .shadow(12.dp, RoundedCornerShape(25.dp), ambientColor = colors.ink, spotColor = colors.ink)
                .background(colors.solid, RoundedCornerShape(25.dp))
                .padding(start = 22.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(shown, style = DaodianType.bodySmall, color = colors.onSolid, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = 44.dp).clickable(onClick = onUndo).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("撤销", style = DaodianType.button, color = colors.onSolid)
            }
        }
    }
}
