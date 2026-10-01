package com.abc.daodian.agent.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.BuildConfig
import com.abc.daodian.agent.conversation.ChatViewModel
import com.abc.daodian.agent.diagnostics.CrashLog
import com.abc.daodian.agent.diagnostics.Diagnostics
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.memory.presentation.MemoryViewModel
import com.abc.daodian.agent.model.provider.ApiState
import com.abc.daodian.agent.update.Updates
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel
import kotlinx.coroutines.launch

/**
 * 设置首页：一列入口，点进去才是设置。见 DESIGN.md §08
 *
 * 三组：助手（模型服务、记忆）、模块（一个模块一行，[FeatureUi.settingsEntries]）、系统（权限与监听）。
 * 只往下走：每一页只链到自己底下的页，子页之间不互相跳。通知使用权、谁听哪些 app 只在「权限与监听」里，
 * 模块页里不放通知的行。体检结论在「权限与监听」页（[PermissionsScreen]），这里只在那一行上写一句，缺了就红。
 * 系统组最后一行是版本号，内测的检查更新也在这一行。
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

            // ---- 模块：一个模块一行，点进模块自己的设置页 ----
            val entries = uis.flatMap { it.settingsEntries }
            if (entries.isNotEmpty()) {
                GroupLabel("模块")
                PaperGroup {
                    entries.forEachIndexed { i, e ->
                        if (i > 0) GroupRule()
                        SettingRow(title = e.title, note = e.note, onClick = { open(e.route) }) {
                            ChevronRightIcon(size = 13.dp, tint = colors.muted)
                        }
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
                GroupRule()
                DiagnosticsRow()
                GroupRule()
                VersionRow()
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 生成一份诊断（版本、权限、闹钟准不准、整理成没成、最近崩溃），交给系统分享发给开发者。不带对话和账的内容 */
@Composable
private fun DiagnosticsRow() {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var crashes by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        crashes = CrashLog.countSince(context, System.currentTimeMillis() - 7 * 24 * 3600_000L)
        onPauseOrDispose { }
    }
    SettingRow(
        title = "导出诊断",
        note = (if (crashes > 0) "这 7 天崩过 $crashes 次 · " else "") + "出了问题点这里，发给开发者。不带对话和账的内容",
        noteColor = if (crashes > 0) colors.red else colors.muted,
        onClick = { scope.launch { Diagnostics.share(context) } }
    ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
}

/** 版本号，内测期间也是检查更新的入口（[Updates]）。有新版本就朱砂字，点了下载、下完交给系统安装 */
@Composable
private fun VersionRow() {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val state by Updates.state.collectAsState()
    LifecycleResumeEffect(Unit) {
        Updates.check()
        onPauseOrDispose { }
    }
    val current = BuildConfig.VERSION_NAME
    val (note, color) = when {
        !Updates.enabled -> "$current · debug 包，不查更新" to colors.muted
        else -> when (val s = state) {
            Updates.State.Unknown -> current to colors.muted
            Updates.State.Latest -> "$current · 已是最新" to colors.muted
            is Updates.State.Available ->
                ("有新版本 ${s.release.versionName}，点一下下载安装，数据都在" +
                    s.release.notes.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()) to colors.accent
            is Updates.State.Downloading ->
                "正在下载 ${s.release.versionName}" + (s.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "") to colors.muted
            is Updates.State.Failed -> "${s.release.versionName} ${s.why}" to colors.red
        }
    }
    SettingRow(
        title = "版本",
        note = note,
        noteColor = color,
        onClick = if (!Updates.enabled) null else {
            {
                when (val s = state) {
                    is Updates.State.Available -> Updates.download(context, s.release)
                    is Updates.State.Failed -> Updates.download(context, s.release)
                    is Updates.State.Downloading -> Unit
                    else -> Updates.check(force = true)
                }
            }
        }
    )
}

/** 查得到、又没开的体检项，名字 */
private fun missingHealth(context: android.content.Context): List<String> =
    FeatureRegistry.health(context).filter { it.ok == false }.map { it.label }
