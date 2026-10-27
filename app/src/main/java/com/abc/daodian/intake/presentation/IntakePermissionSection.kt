package com.abc.daodian.intake.presentation

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.abc.daodian.intake.IntakeRoutes
import com.abc.daodian.intake.core.inbox.InboxState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「权限与监听」页里通知监听层那几组：通知使用权、监听连没连着（重连）、扫一遍，再是规则、收到的通知两个入口
 * （设计稿方向 B：https://claude.ai/artifact/Qg7TytQpTJd5N4CtuacTB8）。全 app 只有这一个监听（DESIGN.md §2.3）。
 * 通知使用权**不算进体检结论**：它挂了听不到通知，但不影响提醒响（DESIGN.md §9.1）。
 */
@Composable
fun IntakePermissionSection(open: (String) -> Unit) {
    val vm = activityViewModel<IntakeViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val listener by vm.listener.collectAsState()
    val action by vm.action.collectAsState()
    var granted by remember { mutableStateOf(vm.granted()) }
    // 从系统设置开完通知使用权回来，当场变
    LifecycleResumeEffect(Unit) {
        granted = vm.granted()
        onPauseOrDispose { }
    }
    val busy = action == ListenerAction.Sweeping || action == ListenerAction.Reconnecting

    GroupLabel("听通知要的")
    PaperGroup {
        SettingRow(
            title = "通知使用权",
            note = if (granted) null else "没开，听不到通知 —— 点这里去系统设置里打开「到点」",
            noteColor = if (granted) colors.muted else colors.red,
            titleColor = if (granted) colors.ink2 else colors.ink,
            onClick = { runCatching { context.startActivity(vm.grantIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            leading = { Marker(granted) }
        ) {
            if (!granted) FixLink()
        }
        if (granted) {
            GroupRule()
            SettingRow(
                title = "监听",
                note = IntakeViewModel.listenerNote(listener) + if (listener.connected) "" else " —— 点「重连」",
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
}

/** 两个入口：规则、收到的通知 */
@Composable
private fun Entries(open: (String) -> Unit) {
    val inbox = activityViewModel<InboxViewModel>()
    val colors = DaodianColors.current
    val rules by inbox.ruleItems.collectAsState()
    val entries by inbox.entries.collectAsState()
    val on = rules.orEmpty().count { it.rule.enabled }
    val off = rules.orEmpty().size - on
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val list = entries.orEmpty()
    val todayCount = list.count { Instant.ofEpochMilli(it.row.receivedAt).atZone(zone).toLocalDate() == today }
    val waiting = list.count { it.row.state in setOf(InboxState.WAITING, InboxState.PENDING, InboxState.WORKING) }
    val failed = list.count { e -> e.outcomes.any { it.tone == Tone.BAD } }

    GroupLabel("听通知")
    PaperGroup {
        SettingRow(
            title = "规则",
            note = if (off > 0) "$off 条停着" else null,
            onClick = { open(IntakeRoutes.RULES) }
        ) {
            if (rules != null) {
                Text("$on", style = DaodianType.settingValue, color = colors.ink)
                Spacer(Modifier.width(10.dp))
            }
            ChevronRightIcon(size = 13.dp, tint = colors.muted)
        }
        GroupRule()
        SettingRow(
            title = "收到的通知",
            note = entries?.let {
                listOfNotNull(
                    "今天 $todayCount 条",
                    if (waiting > 0) "$waiting 条还没办" else null,
                    if (failed > 0) "$failed 条没办成" else null
                ).joinToString(" · ")
            },
            noteColor = if (failed > 0) colors.red else colors.muted,
            onClick = { open(IntakeRoutes.NOTICES) }
        ) {
            ChevronRightIcon(size = 13.dp, tint = colors.muted)
        }
    }
}

/**
 * 设置首页「权限与监听」那一行上，通知监听要说的一句：连着 / 断了 / 没开使用权。
 * 一条规则都没在听时不说 —— 这时候监听断没断都没关系
 */
@Composable
fun intakePermissionStatus(): Pair<String, Boolean>? {
    val vm = activityViewModel<IntakeViewModel>()
    val listener by vm.listener.collectAsState()
    val rules by activityViewModel<InboxViewModel>().ruleItems.collectAsState()
    var granted by remember { mutableStateOf(vm.granted()) }
    LifecycleResumeEffect(Unit) {
        granted = vm.granted()
        onPauseOrDispose { }
    }
    if (rules?.any { it.rule.enabled } != true) return null
    return when {
        !granted -> "没开通知使用权" to false
        !listener.connected -> "监听断了" to false
        else -> "监听" + IntakeViewModel.listenerNote(listener) to true
    }
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
