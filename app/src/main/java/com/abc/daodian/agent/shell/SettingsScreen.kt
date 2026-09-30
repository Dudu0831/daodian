package com.abc.daodian.agent.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.agent.conversation.ChatViewModel
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.memory.presentation.MemoryViewModel
import com.abc.daodian.agent.model.provider.ApiState
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel

/**
 * 设置首页：一列入口，每行写着自己的现状，点进去才是设置。见 DESIGN.md §08
 * 设计稿方向 A：<https://claude.ai/artifact/VFmJaUSSQ4dEjbMN2FRmt2>
 *
 * 三组：助手（模型服务、记忆）、模块（各模块给的行，[FeatureUi.settingsEntries]）、系统（权限与监听）。
 * 体检结论不在这里，在「权限与监听」页（[PermissionsScreen]）；这里只在那一行上写一句，缺了就红。
 * 来设置多半是看「收到了没有」，所以模块那几行写的是现状（今天收到几条、最近一条几点），不是说明。
 */
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    uis: List<FeatureUi>,
    onBack: () -> Unit,
    onOpenProvider: () -> Unit,
    open: (String) -> Unit
) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val profile by vm.profile.collectAsState()
    val api by vm.apiState.collectAsState()
    val memory = activityViewModel<MemoryViewModel>()
    val memoryCount = memory.memories.collectAsState().value?.size
    val autoTidy by memory.autoTidy.collectAsState()

    // 体检每次回到这页都重查：从系统设置开完权限退回来，那一行要当场变
    var missing by remember { mutableStateOf(missingHealth(context)) }
    LifecycleResumeEffect(Unit) {
        missing = missingHealth(context)
        onPauseOrDispose { }
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
            // ---- 助手：模型服务、记忆 ----
            GroupLabel("助手", top = 12.dp)
            PaperGroup {
                val down = api as? ApiState.Down
                SettingRow(
                    title = "模型服务",
                    note = when {
                        !profile.isConfigured -> "网关、key、模型有一项空着，说话办不了事"
                        down != null -> "上次没连上 · ${down.why}"
                        else -> "${profile.model} · ${Format.host(profile.baseUrl)}"
                    },
                    noteColor = if (down != null || !profile.isConfigured) colors.red else colors.muted,
                    onClick = onOpenProvider
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
                GroupRule()
                SettingRow(
                    title = "记忆",
                    note = memoryCount?.let { n ->
                        (if (n == 0) "还没有" else "$n 条") + " · 聊完自己整理" + if (autoTidy) "开着" else "关着"
                    },
                    onClick = { open(ShellRoutes.MEMORY) }
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
            }

            // ---- 模块：每行写现状，点进模块自己的设置页 ----
            val entries = uis.flatMap { it.settingsEntries }
            if (entries.isNotEmpty()) {
                GroupLabel("模块")
                PaperGroup {
                    entries.forEachIndexed { i, e ->
                        if (i > 0) GroupRule()
                        EntryRow(e, onClick = { open(e.route) })
                    }
                }
            }

            // ---- 系统：权限与监听 ----
            GroupLabel("系统")
            PaperGroup {
                val statuses = uis.mapNotNull { it.permissionStatus() }
                val ok = missing.isEmpty() && statuses.all { it.ok }
                val verdict = if (missing.isEmpty()) "都就绪了" else "还差 ${missing.size} 项：" + missing.joinToString("、")
                SettingRow(
                    title = "权限与监听",
                    note = (listOf(verdict) + statuses.map { it.text }).joinToString(" · "),
                    noteColor = if (ok) colors.muted else colors.red,
                    onClick = { open(ShellRoutes.PERMISSIONS) },
                    leading = { Marker(ok) }
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 查得到、又没开的体检项，名字 */
private fun missingHealth(context: android.content.Context): List<String> =
    FeatureRegistry.health(context).filter { it.ok == false }.map { it.label }

/** 模块的一行：标题（带小标）、底下模块自己画的现状，行尾箭头 */
@Composable
private fun EntryRow(entry: SettingsEntry, onClick: () -> Unit) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = DaodianType.rowTitle, color = colors.ink)
                entry.tag?.let { Text(it, style = DaodianType.sectionLabel, color = colors.muted) }
            }
            // 一两行现状，行间同标题和第一行之间一样空 4dp（设计稿里是一列 gap 4）
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { entry.status() }
        }
        Spacer(Modifier.width(14.dp))
        ChevronRightIcon(size = 13.dp, tint = colors.muted)
    }
}
