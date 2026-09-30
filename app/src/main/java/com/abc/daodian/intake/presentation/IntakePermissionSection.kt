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
import com.abc.daodian.intake.Intake
import com.abc.daodian.intake.IntakeRoutes
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

/**
 * 「权限与监听」页里通知监听层那几组：通知使用权、监听连没连着（重连）、扫一遍，
 * 谁在听（按模块，点进勾选页）、按 app 看。全 app 只有这一个监听（DESIGN.md §2.3）。
 * 通知使用权**不算进体检结论**：它挂了记账、派活收不到，但不影响提醒响（DESIGN.md §9.1）。
 */
@Composable
fun IntakePermissionSection(open: (String) -> Unit) {
    val vm = activityViewModel<IntakeViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val listener by vm.listener.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    val routes by vm.routes.collectAsState()
    val action by vm.action.collectAsState()
    var granted by remember { mutableStateOf(vm.granted()) }
    // 从系统设置开完通知使用权回来，当场变
    LifecycleResumeEffect(Unit) {
        granted = vm.granted()
        onPauseOrDispose { }
    }
    val busy = action == ListenerAction.Sweeping || action == ListenerAction.Reconnecting
    val who = Intake.subscribers.joinToString("、") { it.label }

    GroupLabel("${who}要的")
    PaperGroup {
        SettingRow(
            title = "通知使用权",
            note = if (granted) "挂了${who}收不到，不影响提醒响" else "没开，${who}都收不到 —— 点这里去系统设置里打开「到点」",
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
                note = resultOf(action) ?: "实时回调会漏。还挂在通知栏里的，再交给勾了它的模块一次；划掉了的，系统不留",
                noteColor = if (action is ListenerAction.NotConnected) colors.red else colors.muted,
                leading = { Marker(null) }
            ) {
                Action(if (action == ListenerAction.Sweeping) "在扫……" else "扫一遍", enabled = !busy) { vm.sweep() }
            }
        }
    }

    GroupLabel("谁在听")
    PaperGroup {
        subscriptions.orEmpty().forEachIndexed { i, sub ->
            if (i > 0) GroupRule()
            SettingRow(
                title = sub.subscriber.label,
                note = if (sub.apps.isEmpty()) "一个 app 都没勾，什么都收不到" else sub.apps.joinToString("、"),
                onClick = { open(IntakeRoutes.apps(sub.subscriber.id)) }
            ) {
                if (sub.apps.isNotEmpty()) {
                    Text("${sub.apps.size} 个", style = DaodianType.settingValue, color = colors.ink)
                    Spacer(Modifier.width(10.dp))
                }
                ChevronRightIcon(size = 13.dp, tint = colors.muted)
            }
        }
    }

    if (routes.isNotEmpty()) {
        GroupLabel("按 app 看")
        PaperGroup {
            routes.forEachIndexed { i, r ->
                if (i > 0) GroupRule()
                SettingRow(title = r.app, note = "交给" + r.to.joinToString("、"))
            }
        }
        Text(
            "同一个 app 两个模块都勾了，一条通知两边各收一份、各存各的。",
            style = DaodianType.settingNote,
            color = colors.hint,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)
        )
    }
}

/**
 * 设置首页「权限与监听」那一行上，通知监听要说的一句：连着 / 断了 / 没开使用权。
 * 谁都没勾 app 时不说 —— 这时候监听断没断都没关系
 */
@Composable
fun intakePermissionStatus(): Pair<String, Boolean>? {
    val vm = activityViewModel<IntakeViewModel>()
    val listener by vm.listener.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    var granted by remember { mutableStateOf(vm.granted()) }
    LifecycleResumeEffect(Unit) {
        granted = vm.granted()
        onPauseOrDispose { }
    }
    if (subscriptions?.all { it.apps.isEmpty() } != false) return null
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
        val to = s.routed.values.sum()
        (if (action.reconnected) "断了，重连上了。" else "") + "${Format.clock(action.at)} 扫了一次：通知栏里挂着 ${s.active} 条，" +
            (if (to == 0) "没有勾上的 app 的" else "交出去 $to 条（收过的各模块自己去重）")
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
