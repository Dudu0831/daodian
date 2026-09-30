package com.abc.daodian.reminder.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.intake.Intake
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.delivery.HealthCheck
import com.abc.daodian.reminder.relay.RelayMessage
import com.abc.daodian.reminder.relay.RelayStatus
import com.abc.daodian.reminder.relay.state
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/*
 * 提醒在设置里的几样：自己的设置页（当天事项收尾、投递日志），设置首页「提醒」「派活」两行上的现状，
 * 「权限与监听」页体检结论底下那一行（最近投递准不准）。设计稿方向 A：https://claude.ai/artifact/VFmJaUSSQ4dEjbMN2FRmt2
 */

/** 提醒的设置页：当天事项收尾时刻；投递日志 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val vm = activityViewModel<ReminderViewModel>()
    val colors = DaodianColors.current
    val logs by vm.logs.collectAsState()
    val checkTime by vm.dayCheckTime.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
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
                SettingRow(
                    title = "收尾时刻",
                    note = "只说了哪天、没说几点的事，在这个钟点提醒一次；没做完顺延到第二天。",
                    onClick = { pickingCheckTime = true }
                ) {
                    Text(checkTime.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
                }
            }

            GroupLabel("准不准")
            PaperGroup {
                SettingRow(
                    title = "投递日志",
                    note = if (logs.isEmpty()) "还没有投递记录"
                    else "${logs.size} 条 · 最大漂移 ${drift(logs.maxOf { it.driftMillis })}",
                    onClick = { open(ReminderRoutes.LOG) }
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
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

/**
 * 设置首页「提醒」那一行的现状：缺权限、走了兜底补发就写红字（到点可能不响）；好着写收尾时刻和最近投递准不准
 */
@Composable
fun ReminderEntryStatus() {
    val vm = activityViewModel<ReminderViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val logs by vm.logs.collectAsState()
    val nonAlarm by vm.nonAlarmCount.collectAsState()
    val checkTime by vm.dayCheckTime.collectAsState()
    // 从系统设置开完权限回来，这一行要当场变
    var missing by remember { mutableStateOf(missingOf(context)) }
    LifecycleResumeEffect(Unit) {
        missing = missingOf(context)
        onPauseOrDispose { }
    }
    val time = "收尾 ${checkTime.toString().take(5)}"
    when {
        missing.isNotEmpty() -> Line(missing.joinToString("、") + "没开，到点可能不响", colors.red)
        nonAlarm > 0 -> Line("最近 ${logs.size} 次投递里 $nonAlarm 次走了兜底补发 —— 主闹钟在被掐", colors.red)
        logs.isEmpty() -> Line("$time · 还没有投递记录", colors.muted)
        else -> Line("$time · 最近 ${logs.size} 次投递，最大漂移 ${drift(logs.maxOf { it.driftMillis })}", colors.muted)
    }
}

private fun missingOf(context: android.content.Context): List<String> =
    HealthCheck.run(context).filter { !it.ok }.map { it.label }

/**
 * 设置首页「派活」那一行的现状：第一行是她最近一句怎么样了（出了问题写红字：没开使用权、监听断了），
 * 第二行是听谁、暗号
 */
@Composable
fun RelayEntryStatus() {
    val vm = activityViewModel<ReminderViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val relay by vm.relay.collectAsState()
    val latest by vm.relayLatest.collectAsState()
    val apps by vm.relayApps.collectAsState()
    val listener by Intake.listener.collectAsState()
    var granted by remember { mutableStateOf(Intake.granted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = Intake.granted(context)
        onPauseOrDispose { }
    }
    val setup = when {
        relay.who.isBlank() -> "还没设听谁 · 她在微信里说一句，这里接住建成提醒"
        relay.code.isBlank() -> "听「${relay.who}」· 没设暗号，句句都交给模型"
        else -> "听「${relay.who}」· 暗号 ${relay.code}"
    }
    val problem = when {
        relay.who.isBlank() -> null
        apps?.isEmpty() == true -> "还没勾听哪个 app"
        !granted -> "没开通知使用权，她说的收不到"
        !listener.connected -> listener.since?.let { "监听断了 · 她 ${clock(it)} 以后说的接不到" } ?: "监听没连上，她说的接不到"
        else -> null
    }
    if (problem != null) Line(problem, colors.red)
    else if (relay.who.isNotBlank()) Line(latest?.let(::latestOf) ?: "还没收到她的话", colors.ink2)
    Line(setup, colors.muted)
}

/** 「她最近一句 13:40 · 建了提醒」 */
private fun latestOf(m: RelayMessage): String = "她最近一句 ${clock(m.at)} · " + when (m.state) {
    RelayStatus.CREATED -> "建了提醒"
    RelayStatus.NOT_TASK -> "不是待办"
    RelayStatus.SKIPPED -> m.detail ?: "只记下了"
    RelayStatus.WORKING -> "在办"
    RelayStatus.FAILED -> "没办成"
}

/** 设置行里的小字一行 */
@Composable
private fun Line(text: String, color: Color) {
    Text(text, style = DaodianType.settingNote, color = color)
}

/** 今天的写钟点，别的日子带上日期 */
private fun clock(millis: Long): String {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (day == LocalDate.now()) Format.clock(millis) else Format.humanDateTimeShort(millis)
}

/** 「权限与监听」页体检结论底下那一行：最近投递准不准。走了兜底补发就是主闹钟在被掐，写红字 */
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
