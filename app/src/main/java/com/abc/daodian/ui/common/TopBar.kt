package com.abc.daodian.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/** 圆点 + 「到点」wordmark，chat 页专用的顶栏。见 DESIGN.md §02 */
@Composable
fun ChatTopBar(onOpenList: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = DaodianColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 36.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(10.dp).background(colors.green, CircleShape))
            Text("到点", style = DaodianType.wordmark, color = colors.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconTapTarget(onClick = onOpenList) { ListIcon(tint = colors.ink2) }
            IconTapTarget(onClick = onOpenSettings) { SettingsIcon(tint = colors.ink2) }
        }
    }
}

/** 有返回箭头的标准页头，其余页面共用 */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    val colors = DaodianColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 30.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconTapTarget(onClick = onBack) { BackIcon(tint = colors.ink2) }
            Text(title, style = DaodianType.greetingBold, color = colors.ink)
        }
        trailing()
    }
}

/** 34dp 圆形点击区，图标按钮统一走这个，触控目标不小于 44dp 的邻域 */
@Composable
fun IconTapTarget(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 20.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}
