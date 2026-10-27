package com.abc.daodian.shell

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.FixLink
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.OutlineBadge
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shell.entry.HealthItem
import com.abc.daodian.shell.entry.ShellEntries
import com.abc.daodian.shell.entry.ShellEntry

/**
 * 权限与监听：顶上一句体检结论，底下各模块要的系统权限（「提醒要的」），再往下是各模块自己那一组
 * （[ShellEntry.PermissionSection]，通知监听层的使用权、监听、规则和收到的通知）。见 DESIGN.md §9.1
 *
 * 权限以前分在设置页两处（系统权限在上、通知使用权在最底下），现在都在这一页。
 * 体检每次回到这页都重跑：从系统设置开完权限退回来，红字要当场变成对勾，不然会以为没开成功又去开一遍。
 */
@Composable
fun PermissionsScreen(uis: List<ShellEntry>, onBack: () -> Unit, open: (String) -> Unit) {
    val colors = DaodianColors.current
    val context = LocalContext.current

    var groups by remember { mutableStateOf(healthGroups(context)) }
    LifecycleResumeEffect(Unit) {
        groups = healthGroups(context)
        onPauseOrDispose { }
    }

    // 跳不过去（ROM 没有那一页）就退到系统的应用详情页，不让点了没反应
    fun launch(intent: Intent?) {
        intent ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "权限与监听", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Verdict(items = groups.flatMap { it.second }, uis = uis)

            groups.forEach { (feature, items) ->
                GroupLabel("${feature.label}要的")
                PaperGroup {
                    items.forEachIndexed { i, item ->
                        if (i > 0) GroupRule()
                        HealthRow(item, onFix = { launch(item.fixIntent) })
                    }
                }
            }

            uis.forEach { it.PermissionSection(open) }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 各模块给的体检项，按模块分组，没给的不出现 */
private fun healthGroups(context: android.content.Context): List<Pair<ShellEntry, List<HealthItem>>> =
    ShellEntries.entries.map { it to it.health(context) }.filter { it.second.isNotEmpty() }

/**
 * 页头的一句结论：权限齐不齐。全好是墨字 + 朱砂小对勾；有缺口才用红字，写清楚缺几项、缺的是哪几项。
 * 查不到的（只能手动设的）不算。底下各模块可以补一行（[ShellEntry.HealthNote]）
 */
@Composable
private fun Verdict(items: List<HealthItem>, uis: List<ShellEntry>) {
    val colors = DaodianColors.current
    val checked = items.filter { it.ok != null }
    val missing = checked.filter { it.ok == false }

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
            if (missing.isEmpty()) "${checked.size} 项系统权限都开着，到点就会响"
            else missing.joinToString("、") { it.label } + " 没开，到点可能不响",
            style = DaodianType.bodySmall, color = colors.ink2
        )
        Spacer(Modifier.height(2.dp))
        uis.forEach { it.HealthNote() }
    }
}

/**
 * 体检行。好着的只占一行（对勾 + 名字），不再把「已授予」之类的废话摊开；
 * 缺的那项才展开说后果，右边一个「去开」。查不到的写清楚怎么手动设，右边一个「手动」。
 * 有跳转的行不管好没好都整行可点：好着的也可能想进去看一眼、调一下（比如渠道重要性看着是 4，横幅样式还想改），
 * 好着时右边是一个淡箭头
 */
@Composable
private fun HealthRow(item: HealthItem, onFix: () -> Unit) {
    val colors = DaodianColors.current
    val onClick = if (item.fixIntent != null) onFix else null
    if (item.ok == null) {
        SettingRow(title = item.label, note = item.detail, onClick = onClick, leading = { Marker(ok = null) }) { OutlineBadge("手动") }
        return
    }
    SettingRow(
        title = item.label,
        note = if (item.ok) null else item.detail,
        titleColor = if (item.ok) colors.ink2 else colors.ink,
        noteColor = colors.red,
        onClick = onClick,
        leading = { Marker(ok = item.ok) }
    ) {
        when {
            item.fixIntent == null -> {}
            item.ok -> ChevronRightIcon(size = 13.dp, tint = colors.muted)
            else -> FixLink()
        }
    }
}
