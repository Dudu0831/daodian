package com.abc.daodian.ledger.presentation

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.ledger.capture.PaySources
import com.abc.daodian.ledger.data.LedgerSettings
import com.abc.daodian.ledger.data.db.AgentRun
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.FixLink
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel
import java.time.LocalTime

/**
 * 设置页里记账那一组：通知使用权、抓到的通知（进抓取页）、整理间隔、每晚对账、现在整理一次。
 * 不算进体检结论 —— 它挂了不影响提醒响。流程见 DESIGN.md §10
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerSettingsSection(onOpenCapture: () -> Unit) {
    val vm = activityViewModel<LedgerViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    var listening by remember { mutableStateOf(PaySources.granted(context)) }
    val organizeHours by vm.organizeHours.collectAsState()
    val checkTime by vm.checkTime.collectAsState()
    val lastRun by vm.lastRun.collectAsState()
    val pendingRaws by vm.pendingRaws.collectAsState()
    val organizing by vm.organizing.collectAsState()
    val listener by PaySources.listener.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }
    var pickingHours by remember { mutableStateOf(false) }
    // 从系统设置开完通知使用权回来，那一行要当场变
    LifecycleResumeEffect(Unit) {
        listening = PaySources.granted(context)
        onPauseOrDispose { }
    }

    fun launch(intent: Intent?) {
        intent ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    GroupLabel("记账")
    PaperGroup {
        SettingRow(
            title = "通知使用权",
            note = if (listening) "听${PaySources.names}的通知，原样存下来再交给模型整理"
            else "没开，记不了账 —— 点这里去系统设置里打开「到点」",
            noteColor = if (listening) colors.muted else colors.red,
            onClick = { launch(PaySources.grantIntent(context)) }
        ) {
            if (listening) Marker(ok = true) else FixLink()
        }
        GroupRule()
        val dropped = listening && !listener.connected
        SettingRow(
            title = "抓到的通知",
            note = if (dropped) "监听没连着，这会儿付的钱进不来 —— 点进来重连、手动抓一下"
            else "看存下来的原文；漏了的，趁还在通知栏里手动抓一下",
            noteColor = if (dropped) colors.red else colors.muted,
            onClick = onOpenCapture
        ) {
            ChevronRightIcon(size = 13.dp, tint = colors.muted)
        }
        GroupRule()
        SettingRow(
            title = "整理间隔",
            note = "每隔这么久看一眼有没有新通知，有才叫模型；最近 10 分钟到的等下一轮。荣耀可能会拖后。",
            onClick = { pickingHours = true }
        ) {
            Text("$organizeHours 小时", style = DaodianType.settingValue, color = colors.ink)
        }
        GroupRule()
        SettingRow(
            title = "每晚对账",
            note = "先整理一遍，还有没认出来的才弹通知问你；一笔都没有就不打扰。",
            onClick = { pickingCheckTime = true }
        ) {
            Text(checkTime.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
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
