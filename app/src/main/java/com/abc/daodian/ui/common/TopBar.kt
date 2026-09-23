package com.abc.daodian.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.harness.background.AgentActivity
import com.abc.daodian.harness.provider.ApiState
import com.abc.daodian.harness.provider.ProviderProfile
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/**
 * 对话页的顶栏：左边抽屉键，右边朱砂小印。见 DESIGN.md §08 界面
 *
 * 提醒列表、记账、设置都收进左边的抽屉（像别的 AI 聊天 app 那样），顶栏只剩两样。
 * 印章本身能点，点开是模型服务的状态纸签 —— 印和纸签都在 ProviderSeal.kt，见决策 8.4；
 * 后台有 agent 在跑时印外面转一圈细线。
 *
 * 状态栏那一条留空给系统自己画（含常驻的闹钟图标）—— 稿子里 44px 的空白就是这个意思，
 * 我们再画一遍会重影，所以这里只有 statusBarsPadding，没有自绘的状态栏元素。
 */
@Composable
fun ChatTopBar(
    profile: ProviderProfile,
    api: ApiState,
    running: List<AgentActivity.Running>,
    onOpenDrawer: () -> Unit,
    onOpenProvider: () -> Unit
) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 10.dp, end = 14.dp)
            .padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTapTarget(onClick = onOpenDrawer) { MenuIcon(tint = colors.ink2) }
        ProviderSeal(profile = profile, api = api, running = running, onOpenProvider = onOpenProvider)
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
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconTapTarget(onClick = onBack) { BackIcon(tint = colors.ink2) }
            Text(title, style = DaodianType.screenTitle, color = colors.ink)
        }
        trailing()
    }
}

/** 40dp 圆形点击区，图标按钮统一走这个，触控目标不小于 44dp 的邻域 */
@Composable
fun IconTapTarget(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}
