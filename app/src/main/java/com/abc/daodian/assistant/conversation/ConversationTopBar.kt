package com.abc.daodian.assistant.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.runtime.model.ApiState
import com.abc.daodian.runtime.model.Effort
import com.abc.daodian.runtime.model.ProviderProfile
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.ui.IconTapTarget
import com.abc.daodian.shared.ui.MenuIcon

/**
 * 对话页的顶栏：左边抽屉键，右边朱砂小印。见 DESIGN.md §08 界面
 *
 * 提醒列表、记账、设置都收进左边的抽屉（像别的 AI 聊天 app 那样），顶栏只剩两样。
 * 印的浓淡是想多久，按住往下拉换档（ProviderSeal.kt、SealTrack.kt，见决策 8.4）；
 * 后台有 agent 在跑时印慢慢一明一暗。
 *
 * 状态栏那一条留空给系统自己画（含常驻的闹钟图标）—— 稿子里 44px 的空白就是这个意思，
 * 我们再画一遍会重影，所以这里只有 statusBarsPadding，没有自绘的状态栏元素。
 */
@Composable
fun ConversationTopBar(
    profile: ProviderProfile,
    api: ApiState,
    running: List<AgentRuntime.Running>,
    track: SealTrack,
    inUse: Effort?,
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
        ProviderSeal(profile = profile, api = api, running = running, track = track, inUse = inUse, onOpenProvider = onOpenProvider)
    }
}
