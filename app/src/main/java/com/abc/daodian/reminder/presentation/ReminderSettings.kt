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
import com.abc.daodian.reminder.presentation.relay.RelayAddSheet
import com.abc.daodian.reminder.presentation.relay.RelayViewModel
import com.abc.daodian.shared.format.Format
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
import kotlin.math.abs

/*
 * 提醒在设置里的两样：自己的设置页（当天事项、派活、准不准），「权限与监听」页体检结论底下那一行（最近投递准不准）。
 */

/**
 * 提醒的设置页：当天事项（收尾时刻）、派活（名单，一人一行）、准不准（投递日志）。
 * 和记账的设置页一个样子：几组、每行右边是值，点了弹框改或者往下走一页。
 * 派活是设计稿方向 C「一人一页」（https://claude.ai/artifact/AbLfPQLYAAGQGY3LNUF37D）：点一个人进这个人那一页，
 * 「加一个人」从底下弹纸；在那一页上点了「不听了」退回来，撤销条画在这一页。
 * 派活要的通知使用权、听哪些 app 不在这里，在「权限与监听」页（DESIGN.md §2.3）—— 这一页不放通知的行、不往那边跳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val vm = activityViewModel<ReminderViewModel>()
    val relayVm = activityViewModel<RelayViewModel>()
    val colors = DaodianColors.current
    val logs by vm.logs.collectAsState()
    val checkTime by vm.dayCheckTime.collectAsState()
    val people by relayVm.people.collectAsState()
    val counts by relayVm.counts.collectAsState()
    val seen by relayVm.seen.collectAsState()
    val forgotten by relayVm.forgotten.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

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
                    SettingRow(
                        title = "收尾时刻",
                        note = "只说了哪天、没说几点的事，在这个钟点提醒一次；没做完顺延到第二天。",
                        onClick = { pickingCheckTime = true }
                    ) {
                        Text(checkTime.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
                    }
                }

                // 派活：名单上的人在微信里说一句，这里接住交给模型建成提醒。一人一行，点进去是这个人那一页
                GroupLabel("派活")
                PaperGroup {
                    people?.forEach { p ->
                        SettingRow(
                            title = p.name,
                            note = if (p.code.isEmpty()) "句句都交给模型" else "只接「${p.code}」开头的",
                            onClick = { open(ReminderRoutes.relay(p.id)) }
                        ) {
                            counts[p.name]?.takeIf { it > 0 }?.let {
                                Text("$it 句", style = DaodianType.settingValue, color = colors.ink)
                                Spacer(Modifier.width(10.dp))
                            }
                            ChevronRightIcon(size = 13.dp, tint = colors.muted)
                        }
                        GroupRule()
                    }
                    SettingRow(
                        title = "加一个人",
                        note = "他们在微信里说一句，这里接住，建成提醒。",
                        onClick = { adding = true }
                    ) { PlusIcon(tint = colors.ink2) }
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
                // 给撤销条留出地方，最后一组不被它盖住
                Spacer(Modifier.height(96.dp))
            }
        }

        UndoBar(
            label = forgotten?.let { "不听${it.person.name}了" },
            onUndo = relayVm::undoForget,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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

    if (adding) {
        RelayAddSheet(
            // 已经在名单上的不用再挑
            seen = seen.filter { s -> people.orEmpty().none { it.name == s } },
            onAdd = relayVm::add,
            onDismiss = { adding = false }
        )
    }
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
