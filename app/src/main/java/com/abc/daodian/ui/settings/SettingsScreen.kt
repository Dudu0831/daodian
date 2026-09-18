package com.abc.daodian.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.ai.ApiState
import com.abc.daodian.data.FireLog
import com.abc.daodian.ledger.PaySamples
import com.abc.daodian.ui.HealthCheck
import com.abc.daodian.ui.HealthItem
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.CheckIcon
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.common.LedgerGroup
import com.abc.daodian.ui.common.LedgerLabel
import com.abc.daodian.ui.common.LedgerRule
import com.abc.daodian.ui.common.ChevronRightIcon
import com.abc.daodian.ui.common.OutlineBadge
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import java.time.LocalTime
import kotlin.math.abs

/**
 * 设置 + 权限体检。见 DESIGN.md §08、§09.1
 *
 * 版式是一本账：顶上一句结论（体检过没过 + 最近投递准不准），底下四组 ——
 * 提醒、模型服务、系统权限、记录。每组一张纸、行间细线，不再一项一个框。
 *
 * 体检每次回到这页都重跑：从系统设置开完权限退回来，红字要当场变成对勾，
 * 不然会以为没开成功又去开一遍。
 */
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
    val profile by vm.profile.collectAsState()
    val api by vm.apiState.collectAsState()
    val logs by vm.logs.collectAsState()
    val nonAlarm by vm.nonAlarmCount.collectAsState()
    val checkTime by vm.dayCheckTime.collectAsState()
    var pickingCheckTime by remember { mutableStateOf(false) }

    var items by remember { mutableStateOf(HealthCheck.run(context)) }
    var sampling by remember { mutableStateOf(PaySamples.granted(context)) }
    var sampled by remember { mutableIntStateOf(PaySamples.count(context)) }
    LifecycleResumeEffect(Unit) {
        items = HealthCheck.run(context)
        sampling = PaySamples.granted(context)
        sampled = PaySamples.count(context)
        onPauseOrDispose { }
    }

    fun launch(intent: Intent?) {
        intent ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "设置", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Verdict(items = items, logs = logs, nonAlarm = nonAlarm)

            // ---- 提醒 ----
            // 当天事项（只说了哪天、没说几点的）统一在这个钟点提醒一次。见 DESIGN.md §4.3
            LedgerLabel("提醒")
            LedgerGroup {
                SettingRow(
                    title = "当天事项收尾",
                    note = "只说了哪天、没说几点的事，在这个钟点提醒一次；没做完顺延到第二天。",
                    onClick = { pickingCheckTime = true }
                ) {
                    Text(checkTime.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
                }
            }

            // ---- 模型服务 ----
            // 改配置在配置页（顶栏那枚印点开也能到），这里只报个平安 + 一个去处。见决策 8.4
            LedgerLabel("模型服务")
            LedgerGroup {
                val down = api as? ApiState.Down
                SettingRow(
                    title = profile.model.ifBlank { "还没配置" },
                    note = when {
                        !profile.isConfigured -> "网关、key、模型有一项空着，说话建不了提醒"
                        down != null -> "上次没连上 · ${down.why}"
                        else -> hostOf(profile.baseUrl)
                    },
                    noteColor = if (down != null || !profile.isConfigured) colors.red else colors.muted,
                    titleColor = if (profile.model.isBlank()) colors.hint else colors.ink,
                    onClick = onOpenProvider
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
            }

            // ---- 系统权限 ----
            LedgerLabel("系统权限")
            LedgerGroup {
                items.forEachIndexed { i, item ->
                    if (i > 0) LedgerRule()
                    HealthRow(item, onFix = { launch(item.fixIntent) })
                }
                LedgerRule()
                // MagicOS 的「应用启动管理」没有公开 API 可以检测，只能手动设，见 §9.2
                SettingRow(
                    title = "应用启动管理",
                    note = "查不到，只能手动设：设置 → 应用启动管理 → 到点 → 关掉自动管理 → 三个开关全开",
                    leading = { Marker(ok = null) }
                ) { OutlineBadge("手动") }
            }

            // ---- 记录 ----
            LedgerLabel("记录")
            LedgerGroup {
                SettingRow(
                    title = "投递日志",
                    note = if (logs.isEmpty()) "还没有投递记录"
                    else "${logs.size} 条 · 最大漂移 ${drift(logs.maxOf { it.driftMillis })}",
                    onClick = onOpenLog
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
                LedgerRule()
                // 记账调研的采样器，不算进体检结论 —— 它挂了不影响提醒响
                SettingRow(
                    title = "支付通知采样",
                    note = if (sampling) "支付宝、招行、掌上生活 · 已存 $sampled 条"
                    else "没开通知使用权，什么都录不到",
                    onClick = { launch(PaySamples.grantIntent(context)) }
                ) {
                    if (sampling) ChevronRightIcon(size = 13.dp, tint = colors.muted)
                    else FixLink()
                }
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

/**
 * 页头的一句结论。只回答两件事：权限齐不齐、最近响得准不准。
 * 全好是墨字 + 朱砂小对勾；有缺口才用红字，写清楚缺几项、缺的是哪几项。
 */
@Composable
private fun Verdict(items: List<HealthItem>, logs: List<FireLog>, nonAlarm: Int) {
    val colors = DaodianColors.current
    val missing = items.filterNot { it.ok }

    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Text("保活体检", style = DaodianType.sectionLabel, color = colors.muted)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (missing.isEmpty()) "都就绪了" else "还差 ${missing.size} 项",
                style = DaodianType.verdict,
                color = if (missing.isEmpty()) colors.ink else colors.red
            )
            if (missing.isEmpty()) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(26.dp).border(1.dp, colors.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) { CheckIcon(size = 12.dp, tint = colors.accent) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (missing.isEmpty()) "${items.size} 项系统权限都开着，到点就会响"
            else missing.joinToString("、") { it.label } + " 没开，到点可能不响",
            style = DaodianType.bodySmall, color = colors.ink2
        )
        Spacer(Modifier.height(2.dp))
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
}

@Composable
private fun SettingRow(
    title: String,
    note: String? = null,
    titleColor: Color = DaodianColors.current.ink,
    noteColor: Color = DaodianColors.current.muted,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(Modifier.align(Alignment.Top).padding(top = 3.dp)) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = DaodianType.rowTitle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(note, style = DaodianType.settingNote, color = noteColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        trailing()
    }
}

/**
 * 体检行。好着的只占一行（对勾 + 名字），不再把「已授予」之类的废话摊开；
 * 缺的那项才展开说后果，整行可点，右边一个「去开」。
 */
@Composable
private fun HealthRow(item: HealthItem, onFix: () -> Unit) {
    val colors = DaodianColors.current
    SettingRow(
        title = item.label,
        note = if (item.ok) null else item.detail,
        titleColor = if (item.ok) colors.ink2 else colors.ink,
        noteColor = colors.red,
        onClick = if (!item.ok && item.fixIntent != null) onFix else null,
        leading = { Marker(ok = item.ok) }
    ) {
        if (!item.ok && item.fixIntent != null) FixLink()
    }
}

/** 行首的小记号：朱砂对勾 = 好着；红色空圈 = 缺；墨灰小点 = 查不到 */
@Composable
private fun Marker(ok: Boolean?) {
    val colors = DaodianColors.current
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (ok) {
            true -> CheckIcon(size = 12.dp, tint = colors.accent)
            false -> Box(Modifier.size(10.dp).border(1.3.dp, colors.red, CircleShape))
            null -> Box(Modifier.size(5.dp).background(colors.hint, CircleShape))
        }
    }
}

@Composable
private fun FixLink() {
    val colors = DaodianColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("去开", style = DaodianType.caption, color = colors.accent)
        ChevronRightIcon(size = 11.dp, tint = colors.accent)
    }
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

/** 同纸签：地址只留主机名 */
private fun hostOf(baseUrl: String): String =
    baseUrl.substringAfter("://").substringBefore('/').trim()
