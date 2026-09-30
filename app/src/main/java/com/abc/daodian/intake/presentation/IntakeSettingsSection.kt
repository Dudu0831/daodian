package com.abc.daodian.intake.presentation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.intake.IntakeRoutes
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.FixLink
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel

/**
 * 设置页里「通知监听」那一组：通知使用权、监听和谁在听（进「通知监听」页）。
 * 不算进体检结论 —— 它挂了不影响提醒响（DESIGN.md §9.1）。
 */
@Composable
fun IntakeSettingsSection(open: (String) -> Unit) {
    val vm = activityViewModel<IntakeViewModel>()
    val colors = DaodianColors.current
    val context = LocalContext.current
    val listener by vm.listener.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    var granted by remember { mutableStateOf(vm.granted()) }
    // 从系统设置开完通知使用权回来，那一行要当场变
    LifecycleResumeEffect(Unit) {
        granted = vm.granted()
        onPauseOrDispose { }
    }

    GroupLabel("通知监听")
    PaperGroup {
        SettingRow(
            title = "通知使用权",
            note = if (granted) "开着 · 记账、派活要的通知都从这里进" else "没开，记账、派活都收不到 —— 点这里去系统设置里打开「到点」",
            noteColor = if (granted) colors.muted else colors.red,
            onClick = { runCatching { context.startActivity(vm.grantIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        ) {
            if (granted) Marker(ok = true) else FixLink()
        }
        GroupRule()
        val dropped = granted && !listener.connected
        val who = subscriptions?.joinToString(" · ") { s ->
            s.subscriber.label + if (s.apps.isEmpty()) " 没勾" else " ${s.apps.size} 个 app"
        }
        SettingRow(
            title = "监听和谁在听",
            note = if (dropped) "监听没连着，这会儿什么都进不来 —— 点进来重连" else who,
            noteColor = if (dropped) colors.red else colors.muted,
            onClick = { open(IntakeRoutes.STATUS) }
        ) {
            ChevronRightIcon(size = 13.dp, tint = colors.muted)
        }
    }
}
