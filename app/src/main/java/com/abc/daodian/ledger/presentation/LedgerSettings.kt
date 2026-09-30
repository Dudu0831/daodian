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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.intake.Intake
import com.abc.daodian.ledger.data.LedgerSettings
import com.abc.daodian.ledger.data.db.AgentRun
import com.abc.daodian.ledger.data.db.RawNotification
import com.abc.daodian.shared.apps.AppCatalog
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
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/*
 * 记账在设置里的两样：自己的设置页（收 / 整理 / 对账），设置首页「记账」那一行上的现状。
 * 通知使用权、监听连没连着在「权限与监听」页（DESIGN.md §2.3）。流程见 DESIGN.md §10。
 * 设计稿方向 A：https://claude.ai/artifact/VFmJaUSSQ4dEjbMN2FRmt2
 */

/**
 * 记账的设置页：收（抓到的通知、听哪些 app）、整理（现在整理一次、整理间隔、整理员自己打标签）、对账（每晚对账）。
 * 「抓到的通知」排第一行 —— 来这里多半是看收到了没有
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerSettingsScreen(onBack: () -> Unit, onOpenCapture: () -> Unit, onOpenApps: () -> Unit) {
    val vm = activityViewModel<LedgerViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val organizeHours by vm.organizeHours.collectAsState()
    val checkTime by vm.checkTime.collectAsState()
    val lastRun by vm.lastRun.collectAsState()
    val pendingRaws by vm.pendingRaws.collectAsState()
    val organizing by vm.organizing.collectAsState()
    val organizerTags by vm.organizerTags.collectAsState()
    val listened by vm.listened.collectAsState()
    val today by vm.capturedToday.collectAsState()
    val latest by vm.latestRaw.collectAsState()
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
            GroupLabel("收", top = 12.dp)
            PaperGroup {
                SettingRow(
                    title = "抓到的通知",
                    note = received(context, today, latest) + " · " +
                        (if (pendingRaws > 0) "待整理 $pendingRaws 条" else "漏了的点进去手动抓"),
                    onClick = onOpenCapture
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
                GroupRule()
                val names = listened?.let { set ->
                    val collator = Collator.getInstance(Locale.CHINA)
                    set.map { AppCatalog.label(context, it) }.sortedWith(collator)
                }
                SettingRow(
                    title = "听哪些 app",
                    note = when {
                        names == null -> null
                        names.isEmpty() -> "一个都没勾，记不了账 —— 点进来勾上付了钱会发通知的 app"
                        else -> names.joinToString("、")
                    },
                    noteColor = if (names?.isEmpty() == true) colors.red else colors.muted,
                    onClick = onOpenApps
                ) {
                    if (!names.isNullOrEmpty()) {
                        Text("${names.size} 个", style = DaodianType.settingValue, color = colors.ink)
                        Spacer(Modifier.width(10.dp))
                    }
                    ChevronRightIcon(size = 13.dp, tint = colors.muted)
                }
            }

            GroupLabel("整理")
            PaperGroup {
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

/**
 * 设置首页「记账」那一行的现状。好着：第一行「今天收到 6 条 · 最近 14:02 招商银行」，第二行待整理几条、几点对账；
 * 收不进来（没勾 app、没开使用权、监听断了）：第一行换成红字说从几点起进不来，收到的挪到第二行
 */
@Composable
fun LedgerEntryStatus() {
    val vm = activityViewModel<LedgerViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val listened by vm.listened.collectAsState()
    val today by vm.capturedToday.collectAsState()
    val latest by vm.latestRaw.collectAsState()
    val pendingRaws by vm.pendingRaws.collectAsState()
    val checkTime by vm.checkTime.collectAsState()
    val listener by Intake.listener.collectAsState()
    // 从系统设置开完通知使用权回来，这一行要当场变
    var granted by remember { mutableStateOf(Intake.granted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = Intake.granted(context)
        onPauseOrDispose { }
    }
    val problem = when {
        listened?.isEmpty() == true -> "一个 app 都没勾，记不了账"
        !granted -> "没开通知使用权，记不了账"
        !listener.connected -> listener.since?.let { "监听断了 · ${clock(it)} 以后付的钱进不来" } ?: "监听没连上，付的钱进不来"
        else -> null
    }
    val got = received(context, today, latest)
    if (problem != null) {
        Line(problem, colors.red)
        Line(got, colors.muted)
    } else {
        Line(got, colors.ink2)
        Line((if (pendingRaws > 0) "待整理 $pendingRaws 条" else "没有待整理的") + " · ${checkTime.toString().take(5)} 对账", colors.muted)
    }
}

/** 「今天收到 6 条 · 最近 14:02 招商银行」「今天还没收到 · 上一条 9月29日 21:10 支付宝」「还没收到过」 */
private fun received(context: android.content.Context, today: Int, latest: RawNotification?): String {
    latest ?: return "还没收到过"
    val last = "${clock(latest.capturedAt)} ${AppCatalog.label(context, latest.pkg)}"
    return if (today > 0) "今天收到 $today 条 · 最近 $last" else "今天还没收到 · 上一条 $last"
}

/** 今天的写钟点，别的日子带上日期 */
private fun clock(millis: Long): String {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (day == LocalDate.now()) Format.clock(millis) else Format.humanDateTimeShort(millis)
}

@Composable
private fun Line(text: String, color: Color) {
    Text(text, style = DaodianType.settingNote, color = color)
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
