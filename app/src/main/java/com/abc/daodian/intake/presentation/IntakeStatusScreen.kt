package com.abc.daodian.intake.presentation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
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
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow

/**
 * 通知监听：使用权、连没连着（重连）、扫一遍，底下是谁在听哪些 app —— 按模块看（点进去勾），也按 app 看。
 * 全 app 只有这一个监听，记账、派活的通知都从这里进（DESIGN.md §2.3）。
 */
@Composable
fun IntakeStatusScreen(vm: IntakeViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
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

    fun openGrant() {
        runCatching { context.startActivity(vm.grantIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("通知监听", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Note("全 app 只有这一个监听。你给哪个模块勾了哪个 app，那个 app 的通知就交给它；谁都没勾的来了就扔，一条都不存。")

            GroupLabel("监听")
            PaperGroup {
                SettingRow(
                    title = "通知使用权",
                    note = if (granted) null else "没开，一条都收不到 —— 点这里去系统设置里打开「到点」",
                    noteColor = colors.red,
                    onClick = ::openGrant,
                    leading = { Marker(granted) }
                ) {
                    if (granted) Text("开着", style = DaodianType.settingValue, color = colors.muted) else FixLink()
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
                        onClick = { onOpen(IntakeRoutes.apps(sub.subscriber.id)) }
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
                Note("同一个 app 两个模块都勾了，一条通知两边各收一份，各存各的。", Modifier.padding(top = 10.dp))
            }
        }
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

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = DaodianType.settingNote,
        color = DaodianColors.current.muted,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp)
    )
}
