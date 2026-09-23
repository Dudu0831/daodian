package com.abc.daodian.reminder.presentation

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
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel
import java.time.LocalTime
import kotlin.math.abs

/** 设置页里提醒的两组：当天事项收尾时刻；投递日志 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsSection(open: (String) -> Unit) {
    val vm = activityViewModel<ReminderViewModel>()
    val colors = DaodianColors.current
    val logs by vm.logs.collectAsState()
    val checkTime by vm.dayCheckTime.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }

    // 当天事项（只说了哪天、没说几点的）统一在这个钟点提醒一次。见 DESIGN.md §4.3
    GroupLabel("提醒")
    PaperGroup {
        SettingRow(
            title = "当天事项收尾",
            note = "只说了哪天、没说几点的事，在这个钟点提醒一次；没做完顺延到第二天。",
            onClick = { pickingCheckTime = true }
        ) {
            Text(checkTime.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
        }
    }

    GroupLabel("记录")
    PaperGroup {
        SettingRow(
            title = "投递日志",
            note = if (logs.isEmpty()) "还没有投递记录"
            else "${logs.size} 条 · 最大漂移 ${drift(logs.maxOf { it.driftMillis })}",
            onClick = { open(ReminderRoutes.LOG) }
        ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
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

/** 设置页体检结论底下那一行：最近响得准不准。走了兜底补发就是主闹钟在被掐，写红字 */
@Composable
fun DeliveryNote() {
    val vm = activityViewModel<ReminderViewModel>()
    val colors = DaodianColors.current
    val logs by vm.logs.collectAsState()
    val nonAlarm by vm.nonAlarmCount.collectAsState()
    val drifts = logs.map { it.driftMillis }.sorted()
    Text(
        when {
            drifts.isEmpty() -> "还没有投递记录"
            nonAlarm > 0 -> "最近 ${drifts.size} 次投递里 $nonAlarm 次走了兜底补发 —— 主闹钟在被掐"
            else -> "最近 ${drifts.size} 次投递 · 中位漂移 ${drift(drifts[drifts.size / 2])} · 全走主闹钟"
        },
        style = DaodianType.caption,
        color = if (nonAlarm > 0) colors.red else colors.muted
    )
}

/** 漂移：十秒以内留一位小数（看得出是 0.2s 还是 0.9s），一分钟以内取整秒，再大就写人话时长 */
private fun drift(ms: Long): String {
    val sign = if (ms >= 0) "+" else "−"
    val a = abs(ms)
    return when {
        a < 10_000 -> "$sign${"%.1f".format(a / 1000.0)}s"
        a < 60_000 -> "$sign${a / 1000}s"
        else -> sign + Format.span(a)
    }
}
