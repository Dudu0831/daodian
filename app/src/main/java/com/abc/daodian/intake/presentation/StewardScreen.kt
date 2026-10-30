package com.abc.daodian.intake.presentation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import com.abc.daodian.intake.Intake
import com.abc.daodian.intake.IntakeModule
import com.abc.daodian.intake.IntakeRoutes
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.intake.legacy.OldSetup
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.FixLink
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel
import com.abc.daodian.shell.entry.SettingsStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 通知管家的页（设置 → 功能 → 通知管家）：监听连没连着（重连、扫一遍），再是规则、收到的通知两个入口。
 * 全 app 只有这一个监听（DESIGN.md §2.3）。设计稿方向 A：https://claude.ai/artifact/VhupokZXiZjFpi4iijS72w
 * 通知使用权本身在「权限」页（[StewardPermissionSection]）；这里没开时只写一行红字、点了去开。
 * 0.1.x 在设置里勾过记账、派活的，最上面多一组照着列出来（[OldSetupGroup]，0.2.1 删）。
 */
@Composable
fun StewardScreen(onBack: () -> Unit, open: (String) -> Unit, say: (String) -> Unit) {
    val vm = activityViewModel<IntakeViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val listener by vm.listener.collectAsState()
    val action by vm.action.collectAsState()
    val granted = rememberGranted(vm)
    val busy = action == ListenerAction.Sweeping || action == ListenerAction.Reconnecting

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(IntakeModule.LABEL, onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            val old by vm.oldSetup.collectAsState()
            old?.takeIf { !it.isEmpty }?.let { OldSetupGroup(it, vm::appName, say) }
            GroupLabel("监听", top = if (old?.isEmpty == false) 28.dp else 12.dp)
            PaperGroup {
                if (!granted) {
                    SettingRow(
                        title = "没开通知使用权",
                        note = "听不到通知",
                        noteColor = colors.red,
                        onClick = { runCatching { context.startActivity(vm.grantIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        leading = { Marker(false) }
                    ) { FixLink() }
                } else {
                    SettingRow(
                        title = if (listener.connected) "连着" else "断了",
                        note = sinceNote(listener),
                        noteColor = if (listener.connected) colors.muted else colors.red,
                        leading = { Marker(listener.connected) }
                    ) {
                        Action(if (action == ListenerAction.Reconnecting) "在重连……" else "重连", enabled = !busy) { vm.reconnect() }
                    }
                    GroupRule()
                    SettingRow(
                        title = "扫一遍通知栏",
                        note = resultOf(action),
                        noteColor = if (action is ListenerAction.NotConnected) colors.red else colors.muted,
                        leading = { Marker(null) }
                    ) {
                        Action(if (action == ListenerAction.Sweeping) "在扫……" else "扫一遍", enabled = !busy) { vm.sweep() }
                    }
                }
            }
            Entries(open)
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 0.1.x 在设置里勾的：记账听的 app、派活听的人。0.2.0 不搬过来，这里照着列，点一行把它垫成一句话放进对话输入框，
 * 你看一眼再发。只在旧配置里有东西时出现；0.2.1 连 [OldSetup] 一起删（docs/cleanup.md）
 */
@Composable
private fun OldSetupGroup(old: OldSetup.Setup, appName: (String) -> String, say: (String) -> Unit) {
    val colors = DaodianColors.current
    GroupLabel("以前设置里勾的", top = 12.dp)
    PaperGroup {
        if (old.hasLedger) {
            val apps = old.ledgerApps.map(appName)
            SettingRow(title = "记账", note = apps.joinToString("、"), onClick = { say(OldSetup.ledgerSay(apps)) }) {
                ChevronRightIcon(size = 13.dp, tint = colors.muted)
            }
        }
        if (old.hasLedger && old.hasRelay) GroupRule()
        if (old.hasRelay) {
            val apps = old.relayApps.map(appName)
            SettingRow(
                title = "派活",
                note = listOfNotNull(apps.joinToString("、").ifEmpty { null }, OldSetup.peopleText(old.people)).joinToString(" · "),
                onClick = { say(OldSetup.relaySay(apps, old.people)) }
            ) {
                ChevronRightIcon(size = 13.dp, tint = colors.muted)
            }
        }
    }
}

/** 两个入口：规则、收到的通知。右边是数（在听的规则、今天进来的），底下只写要你管的 */
@Composable
private fun Entries(open: (String) -> Unit) {
    val inbox = activityViewModel<InboxViewModel>()
    val colors = DaodianColors.current
    val rules by inbox.ruleItems.collectAsState()
    val entries by inbox.entries.collectAsState()
    val on = rules.orEmpty().count { it.rule.enabled }
    val off = rules.orEmpty().size - on
    val list = entries.orEmpty()
    val waiting = list.count { it.row.state in setOf(InboxState.WAITING, InboxState.PENDING, InboxState.WORKING) }
    val failed = list.count { e -> e.outcomes.any { it.tone == Tone.BAD } }
    GroupLabel("规则")
    PaperGroup {
        SettingRow(
            title = "规则",
            note = if (off > 0) "$off 条停着" else null,
            onClick = { open(IntakeRoutes.RULES) }
        ) {
            if (rules != null) Value("$on")
            ChevronRightIcon(size = 13.dp, tint = colors.muted)
        }
        GroupRule()
        SettingRow(
            title = "收到的通知",
            note = listOfNotNull(
                if (waiting > 0) "$waiting 条还没办" else null,
                if (failed > 0) "$failed 条没办成" else null
            ).joinToString(" · ").ifEmpty { null },
            noteColor = if (failed > 0) colors.red else colors.muted,
            onClick = { open(IntakeRoutes.NOTICES) }
        ) {
            if (entries != null) Value("${todayCount(list)}")
            ChevronRightIcon(size = 13.dp, tint = colors.muted)
        }
    }
}

/** 「权限」页里通知管家那一组：只有通知使用权。**不算进体检结论**：它挂了听不到通知，但不影响提醒响（DESIGN.md §9.1） */
@Composable
fun StewardPermissionSection() {
    val vm = activityViewModel<IntakeViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val granted = rememberGranted(vm)
    GroupLabel("${IntakeModule.LABEL}要的")
    PaperGroup {
        SettingRow(
            title = "通知使用权",
            note = if (granted) null else "没开，听不到通知",
            noteColor = colors.red,
            titleColor = if (granted) colors.ink2 else colors.ink,
            onClick = { runCatching { context.startActivity(vm.grantIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            leading = { Marker(granted) }
        ) {
            if (granted) ChevronRightIcon(size = 13.dp, tint = colors.muted) else FixLink()
        }
    }
}

/**
 * 设置首页「通知管家」那一行：连着 · 今天 N 条（右边在听的规则条数）；没开使用权、监听断了写红字。
 * 一条规则都没在听时只写规则数 0 —— 这时候监听断没断都没关系
 */
@Composable
fun stewardSettingsStatus(): SettingsStatus {
    val vm = activityViewModel<IntakeViewModel>()
    val inbox = activityViewModel<InboxViewModel>()
    val listener by vm.listener.collectAsState()
    val rules by inbox.ruleItems.collectAsState()
    val entries by inbox.entries.collectAsState()
    val granted = rememberGranted(vm)
    val on = rules?.count { it.rule.enabled }
    val today = entries?.let { "今天 ${todayCount(it)} 条" }
    val (note, ok) = when {
        on == 0 -> (today ?: "") to true
        !granted -> "没开通知使用权" to false
        !listener.connected -> "监听断了" to false
        else -> listOfNotNull("连着", today).joinToString(" · ") to true
    }
    return SettingsStatus(note = note, ok = ok, value = on?.toString())
}

/** 从系统设置开完通知使用权回来，当场变 */
@Composable
private fun rememberGranted(vm: IntakeViewModel): Boolean {
    var granted by remember { mutableStateOf(vm.granted()) }
    LifecycleResumeEffect(Unit) {
        granted = vm.granted()
        onPauseOrDispose { }
    }
    return granted
}

private fun todayCount(list: List<Entry>): Int {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    return list.count { Instant.ofEpochMilli(it.row.receivedAt).atZone(zone).toLocalDate() == today }
}

/** 「从今天 09:12 起」「从 10-27 09:12 起断了」 */
private fun sinceNote(l: Intake.Listener): String = when {
    l.since == null -> "这次打开 app 以来还没连上过"
    else -> {
        val day = Instant.ofEpochMilli(l.since).atZone(ZoneId.systemDefault()).toLocalDate()
        val at = if (day == LocalDate.now()) "今天 ${Format.clock(l.since)}" else Format.humanDateTimeShort(l.since)
        "从 $at 起"
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = DaodianType.settingValue, color = DaodianColors.current.ink)
    Spacer(Modifier.width(10.dp))
}

/** 扫一遍 / 重连之后那句话 */
private fun resultOf(action: ListenerAction): String? = when (action) {
    is ListenerAction.Swept -> {
        val s = action.sweep
        (if (action.reconnected) "断了，重连上了。" else "") + "${Format.clock(action.at)} 扫了一次：通知栏里挂着 ${s.active} 条，" +
            (if (s.stored == 0) "没有新进来的" else "进来 ${s.stored} 条")
    }
    is ListenerAction.Reconnected -> "${Format.clock(action.at)} 重连上了"
    is ListenerAction.NotConnected -> "${Format.clock(action.at)} 没连上，系统不肯把它绑回来。去系统设置把「到点」的通知使用权关掉再打开"
    else -> null
}

@Composable
private fun Action(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = DaodianColors.current
    Text(
        text,
        style = DaodianType.caption,
        color = if (enabled) colors.accent else colors.hint,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
    )
}
