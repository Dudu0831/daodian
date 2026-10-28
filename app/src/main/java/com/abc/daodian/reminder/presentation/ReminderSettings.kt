package com.abc.daodian.reminder.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.presentation.log.Accuracy
import com.abc.daodian.reminder.presentation.log.drift
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.PlusIcon
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.UndoBar
import com.abc.daodian.shared.ui.activityViewModel
import java.time.LocalTime


/**
 * 提醒的设置页：当天事项（收尾时刻）、准不准（响得准不准 ›，统计页）。
 * 和记账的设置页一个样子：几组、每行右边是值，点了弹框改或者往下走一页。
 * 以前的派活（别人在微信里说一句建成提醒）换成了通知管家的规则（DESIGN.md §12）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val vm = activityViewModel<ReminderViewModel>()
    val colors = DaodianColors.current
    val logs by vm.logs.collectAsState()
    val checkTime by vm.dayCheckTime.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("提醒", onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                // 当天事项（只说了哪天、没说几点的）统一在这个钟点提醒一次。见 DESIGN.md §4.3
                GroupLabel("当天事项", top = 12.dp)
                PaperGroup {
                    SettingRow(title = "没说几点的，几点提醒", onClick = { pickingCheckTime = true }) {
                        Text(checkTime.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
                    }
                }

                GroupLabel("准不准")
                PaperGroup {
                    val a = Accuracy.of(logs)
                    SettingRow(
                        title = "响得准不准",
                        note = when (a.verdict) {
                            Accuracy.Verdict.NONE -> null
                            Accuracy.Verdict.SQUEEZED -> "最近 ${a.count} 次里 ${a.fallbacks} 次走了兜底补发"
                            else -> "最近 ${a.count} 次 · 中位 ${drift(a.medianMillis)}"
                        },
                        noteColor = if (a.verdict.ok) colors.muted else colors.red,
                        onClick = { open(ReminderRoutes.LOG) }
                    ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
                }
                Spacer(Modifier.height(32.dp))
            }
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
