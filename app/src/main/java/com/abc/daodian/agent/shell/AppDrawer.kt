package com.abc.daodian.agent.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.SettingsIcon

/**
 * 左边的抽屉（设计稿方向 B「两张纸」：<https://claude.ai/artifact/PRk3CWeu24V4tKZgxkGLwn>）。
 *
 * 壳只画框：顶上「到点 · 回到对话」，中间每个模块一张纸（[FeatureUi.DrawerCard]，不点进去也看得到要紧的），
 * 设置压在最底下，右边一句体检结论。加模块就多一张纸。
 */
@Composable
fun AppDrawer(
    uis: List<FeatureUi>,
    healthMissing: Int,
    onClose: () -> Unit,
    open: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = DaodianColors.current
    Column(
        Modifier
            .width(318.dp)
            .fillMaxHeight()
            .background(colors.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("到点", style = DaodianType.screenTitle.copy(letterSpacing = 0.12.sp), color = colors.ink)
            Text(
                "回到对话", style = DaodianType.caption, color = colors.muted,
                modifier = Modifier.clickable(onClick = onClose).padding(vertical = 12.dp, horizontal = 4.dp)
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            uis.forEach { it.DrawerCard(open) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 10.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsIcon(tint = colors.ink2)
            Text("设置", style = DaodianType.body, color = colors.ink2, modifier = Modifier.weight(1f))
            Text(
                if (healthMissing == 0) "一切正常" else "还差 $healthMissing 项",
                style = DaodianType.caption,
                color = if (healthMissing == 0) colors.muted else colors.red
            )
        }
    }
}
