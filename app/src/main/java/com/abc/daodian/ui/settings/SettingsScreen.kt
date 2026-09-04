package com.abc.daodian.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.HealthCheck
import com.abc.daodian.ui.HealthItem
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.ChevronRightIcon
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/** 设置 + 权限体检。见 DESIGN.md §08、§09.1 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenLog: () -> Unit) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val items = remember { HealthCheck.run(context) }
    val allGood = items.all { it.ok }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "设置", onBack = onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {

            SectionLabel("模型服务")
            InfoRow("服务地址", vm.profile.baseUrl.ifBlank { "未配置" })
            InfoRow("模型", vm.profile.model.ifBlank { "未配置" })
            InfoRow("接口风格", vm.profile.apiStyle.name)
            InfoRow("密钥", if (vm.profile.apiKey.isBlank()) "未配置" else "已配置 · 尾号 ${vm.profile.apiKey.takeLast(4)}")
            Text(
                "在 secrets.properties 里改，重新编译生效。设置页里改配置还没做，见 README。",
                style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            Spacer(Modifier.height(32.dp))
            SectionLabel(if (allGood) "权限体检 · 全部就绪" else "权限体检")
            items.forEach { item ->
                HealthRow(item)
                Spacer(Modifier.height(10.dp))
            }

            Text(
                "MagicOS 的「应用启动管理」没有公开 API 可以检测，只能手动设 —— " +
                    "设置 → 应用启动管理 → 到点 → 关掉自动管理 → 三个开关全开。",
                style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Spacer(Modifier.height(28.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(5.dp))
                    .border(1.dp, colors.rule, RoundedCornerShape(5.dp))
                    .clickable(onClick = onOpenLog)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("投递日志", style = DaodianType.rowTitle, color = colors.ink)
                ChevronRightIcon(size = 14.dp, tint = colors.muted)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = DaodianColors.current
    Text(text, style = DaodianType.sectionLabel, color = colors.muted, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = DaodianType.caption, color = colors.muted)
        Text(value, style = DaodianType.bodySmall, color = colors.ink2)
    }
    HorizontalDivider(color = colors.rule)
}

@Composable
private fun HealthRow(item: HealthItem) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(5.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(5.dp))
            .padding(14.dp)
    ) {
        Text(
            (if (item.ok) "✓  " else "✗  ") + item.label,
            style = DaodianType.bodySmall,
            color = if (item.ok) colors.ink else colors.red
        )
        Spacer(Modifier.height(4.dp))
        Text(item.detail, style = DaodianType.caption, color = colors.muted)
        val fix = item.fixIntent
        if (!item.ok && fix != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "去设置 ›", style = DaodianType.caption, color = colors.accent,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(fix.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            )
        }
    }
}
