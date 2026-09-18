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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abc.daodian.ledger.PaySamples
import com.abc.daodian.ui.HealthCheck
import com.abc.daodian.ui.HealthItem
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.ChevronRightIcon
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import java.time.LocalTime

/** 设置 + 权限体检。见 DESIGN.md §08、§09.1 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenProvider: () -> Unit
) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val items = remember { HealthCheck.run(context) }
    val profile by vm.profile.collectAsState()
    val allGood = items.all { it.ok }
    val checkTime by vm.dayCheckTime.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "设置", onBack = onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {

            // 供应商在配置页里改（顶栏那枚印点开也能到）。这里只留一行去处，
            // 不再把四项摊开只读着看 —— 摊开也改不了，反倒像个死胡同
            SectionLabel("模型服务")
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(5.dp))
                    .border(1.dp, colors.rule, RoundedCornerShape(5.dp))
                    .clickable(onClick = onOpenProvider)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(profile.model.ifBlank { "还没配置" }, style = DaodianType.rowTitle, color = colors.ink)
                ChevronRightIcon(size = 14.dp, tint = colors.muted)
            }

            // 当天事项（只说了哪天、没说几点的）统一在这个钟点提醒一次。见 DESIGN.md §4.3
            Spacer(Modifier.height(32.dp))
            SectionLabel("当天事项")
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(5.dp))
                    .border(1.dp, colors.rule, RoundedCornerShape(5.dp))
                    .clickable { pickingCheckTime = true }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("晚上几点提醒", style = DaodianType.rowTitle, color = colors.ink)
                Text(checkTime.toString().take(5), style = DaodianType.rowTitle, color = colors.ink2)
            }
            Text(
                "只说了哪天、没说几点的事，在这个钟点提醒一次；没做完的顺延到第二天，还是这个钟点。",
                style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(top = 8.dp)
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

            // 记账调研的采样器，不算进上面的体检 —— 它挂了不影响提醒响
            Spacer(Modifier.height(32.dp))
            SectionLabel("支付通知采样")
            val sampling = remember { PaySamples.granted(context) }
            val sampled = remember { PaySamples.count(context) }
            HealthRow(
                HealthItem(
                    label = "通知使用权",
                    ok = sampling,
                    detail = if (sampling) "在录支付宝、招商银行、掌上生活的通知，已存 $sampled 条"
                    else "没开就什么都录不到",
                    fixIntent = PaySamples.grantIntent(context)
                )
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

    if (pickingCheckTime) {
        val state = rememberTimePickerState(initialHour = checkTime.hour, initialMinute = checkTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingCheckTime = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.setDayCheckTime(LocalTime.of(state.hour, state.minute))
                    pickingCheckTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingCheckTime = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = DaodianColors.current
    Text(text, style = DaodianType.sectionLabel, color = colors.muted, modifier = Modifier.padding(vertical = 12.dp))
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
