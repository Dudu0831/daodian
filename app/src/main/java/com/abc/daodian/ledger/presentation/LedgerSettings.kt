package com.abc.daodian.ledger.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.ledger.data.LedgerSettings
import com.abc.daodian.ledger.data.db.AgentRun
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.PaperSwitch
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel
import java.time.LocalTime

/**
 * 记账的设置页：整理（抓到的通知、现在整理一次、整理间隔、整理员自己打标签）、对账（每晚对账）。流程见 DESIGN.md §10。
 * 和提醒的设置页一个样子：几组、每行右边是值；「抓到的通知」往下走一页看记录，同提醒页的「她发来的」。
 * 通知的事（使用权、听哪些 app）不在这里，在「权限与监听」页（DESIGN.md §2.3）—— 这一页不放通知的行、不往那边跳
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerSettingsScreen(onBack: () -> Unit, onOpenCapture: () -> Unit) {
    val vm = activityViewModel<LedgerViewModel>()
    val colors = DaodianColors.current
    val organizeHours by vm.organizeHours.collectAsState()
    val checkTime by vm.checkTime.collectAsState()
    val lastRun by vm.lastRun.collectAsState()
    val pendingRaws by vm.pendingRaws.collectAsState()
    val rawCount by vm.rawCount.collectAsState()
    val organizing by vm.organizing.collectAsState()
    val organizerTags by vm.organizerTags.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }
    var pickingHours by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("记账", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            GroupLabel("整理", top = 12.dp)
            PaperGroup {
                // 记录：说明写死，只有右边的条数会变 —— 打开时行高不跳
                SettingRow(
                    title = "抓到的通知",
                    note = "付钱的通知原文，后来进了哪一笔。",
                    onClick = onOpenCapture
                ) {
                    rawCount?.takeIf { it > 0 }?.let {
                        Text("$it 条", style = DaodianType.settingValue, color = colors.ink)
                        Spacer(Modifier.width(10.dp))
                    }
                    ChevronRightIcon(size = 13.dp, tint = colors.muted)
                }
                GroupRule()
                SettingRow(
                    title = if (organizing) "正在整理……" else "现在整理一次",
                    note = runNote(lastRun, pendingRaws, organizing),
                    noteColor = if (lastRun?.error != null) colors.red else colors.muted,
                    onClick = if (organizing) null else ({ vm.organizeNow() })
                ) {
                    if (!organizing) ChevronRightIcon(size = 13.dp, tint = colors.muted)
                }
                GroupRule()
                SettingRow(
                    title = "整理间隔",
                    note = "有新通知才叫模型；最近 10 分钟到的等下一轮。荣耀可能会拖后。",
                    onClick = { pickingHours = true }
                ) {
                    Text("$organizeHours 小时", style = DaodianType.settingValue, color = colors.ink)
                }
                GroupRule()
                SettingRow(
                    title = "整理员自己打标签",
                    note = if (organizerTags) "照你打过的学：只用你打过的标签，有把握才打，不新建"
                    else "关着 · 标签只由你打：对账时点、账单页上点、对话里说",
                    onClick = { vm.setOrganizerTags(!organizerTags) }
                ) {
                    PaperSwitch(checked = organizerTags, onCheckedChange = { vm.setOrganizerTags(it) })
                }
            }

            GroupLabel("对账")
            PaperGroup {
                SettingRow(
                    title = "每晚对账",
                    note = "先整理一遍，还有没认出来的才弹通知问你；一笔都没有就不打扰。",
                    onClick = { pickingCheckTime = true }
                ) {
                    Text(checkTime.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
                }
            }
        }
    }

    if (pickingHours) {
        AlertDialog(
            onDismissRequest = { pickingHours = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingHours = false }) { Text("取消") } },
            title = { Text("多久整理一次") },
            text = {
                Column {
                    LedgerSettings.ORGANIZE_CHOICES.forEach { h ->
                        Text(
                            "$h 小时" + if (h == LedgerSettings.DEFAULT_ORGANIZE_HOURS) "（默认）" else "",
                            style = DaodianType.body,
                            color = if (h == organizeHours) colors.accent else colors.ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.setOrganizeHours(h); pickingHours = false }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        )
    }

    if (pickingCheckTime) {
        val state = rememberTimePickerState(initialHour = checkTime.hour, initialMinute = checkTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingCheckTime = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.setCheckTime(LocalTime.of(state.hour, state.minute))
                    pickingCheckTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickingCheckTime = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }
}

/** 「上次 14:05 整理 · 看了 12 条，记了 5 笔 · 还有 3 条等下一轮」 */
private fun runNote(run: AgentRun?, pending: Int, running: Boolean): String {
    val tail = if (pending > 0) " · 还有 $pending 条待整理" else ""
    if (running) return "模型在读通知，读完了这里会写记了几笔$tail"
    if (run == null) return "还没整理过$tail"
    val at = Format.humanDateTimeShort(run.startedAt)
    run.error?.let { return "上次 $at 没整理完：$it$tail" }
    return "上次 $at · 看了 ${run.rawCount} 条，记了 ${run.recorded} 笔$tail"
}
