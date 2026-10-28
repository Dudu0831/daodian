package com.abc.daodian.shell

import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.BuildConfig
import com.abc.daodian.assistant.AssistantRoutes
import com.abc.daodian.assistant.conversation.ConversationViewModel
import com.abc.daodian.assistant.memory.presentation.MemoryViewModel
import com.abc.daodian.runtime.model.ApiState
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.activityViewModel
import com.abc.daodian.shell.diagnostics.CrashLog
import com.abc.daodian.shell.diagnostics.Diagnostics
import com.abc.daodian.shell.entry.ShellEntries
import com.abc.daodian.shell.entry.ShellEntry
import com.abc.daodian.update.Updates
import kotlinx.coroutines.launch

/**
 * 设置首页：一列入口，点进去才是设置。见 DESIGN.md §08，设计稿方向 A：https://claude.ai/artifact/VhupokZXiZjFpi4iijS72w
 *
 * 三组：助手（模型服务、记忆）、功能（一个模块一行，[ShellEntry.settingsEntries]：提醒、记账、通知管家）、
 * 系统（权限、导出诊断、版本）。只往下走：每一页只链到自己底下的页，子页之间不互相跳。
 * 行首不放记号，状态只用字说：好着是灰字，出了问题同一个位置写红字。固定的说明不写，只写会变的数。
 */
@Composable
fun SettingsScreen(
    vm: ConversationViewModel,
    uis: List<ShellEntry>,
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
                        !profile.isConfigured -> "没配好，说话办不了事"
                        down != null -> "上次没连上 · ${down.why}"
                        else -> "${profile.model} · ${Format.host(profile.baseUrl)}"
                    },
                    noteColor = if (down != null || !profile.isConfigured) colors.red else colors.muted,
                    onClick = onOpenProvider
                ) { Chevron() }
                GroupRule()
                SettingRow(title = "记忆", onClick = { open(AssistantRoutes.MEMORY) }) {
                    memoryCount?.takeIf { it > 0 }?.let { Value("$it") }
                    Chevron()
                }
            }

            // ---- 功能：一个模块一行，点进模块自己的设置页 ----
            val rows = uis.flatMap { ui -> ui.settingsEntries.map { ui to it } }
            if (rows.isNotEmpty()) {
                GroupLabel("功能")
                PaperGroup {
                    rows.forEachIndexed { i, (ui, e) ->
                        if (i > 0) GroupRule()
                        val status = ui.settingsStatus()
                        SettingRow(
                            title = e.title,
                            note = status?.note,
                            noteColor = if (status?.ok == false) colors.red else colors.muted,
                            onClick = { open(e.route) }
                        ) {
                            status?.value?.let { Value(it) }
                            Chevron()
                        }
                    }
                }
            }

            // ---- 系统：权限、导出诊断、版本 ----
            GroupLabel("系统")
            PaperGroup {
                SettingRow(title = "权限", onClick = { open(ShellRoutes.PERMISSIONS) }) {
                    Text(
                        if (missing.isEmpty()) "都就绪了" else "还差 ${missing.size} 项",
                        style = DaodianType.caption,
                        color = if (missing.isEmpty()) colors.muted else colors.red
                    )
                    Spacer(Modifier.width(10.dp))
                    Chevron()
                }
                GroupRule()
                DiagnosticsRow()
                GroupRule()
                VersionRow()
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 行尾的数：记忆几条、规则几条、版本号 */
@Composable
private fun Value(text: String, gap: Boolean = true) {
    Text(text, style = DaodianType.settingValue, color = DaodianColors.current.ink)
    if (gap) Spacer(Modifier.width(10.dp))
}

@Composable
private fun Chevron() = ChevronRightIcon(size = 13.dp, tint = DaodianColors.current.muted)

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
        note = if (crashes > 0) "这 7 天崩过 $crashes 次" else null,
        noteColor = colors.red,
        onClick = { scope.launch { Diagnostics.share(context) } }
    ) { Chevron() }
}

/** 版本号，内测期间也是检查更新的入口（[Updates]）。有新版本就朱砂字，点了下载、下完交给系统安装 */
@Composable
private fun VersionRow() {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val state by Updates.state.collectAsState()
    LifecycleResumeEffect(Unit) {
        Updates.check(context)
        onPauseOrDispose { }
    }
    val current = BuildConfig.VERSION_NAME
    // 版本号写在右边；底下一句只在有事时出现：有新版、在下、下好了、下失败了
    val (note, color) = when (val s = if (Updates.enabled) state else null) {
        is Updates.State.Available ->
            ("有新版本 ${s.release.versionName}，点一下下载安装，数据都在" +
                s.release.notes.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()) to colors.accent
        is Updates.State.Downloading ->
            "正在下载 ${s.release.versionName}" + (s.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "") to colors.muted
        is Updates.State.Ready -> "${s.release.versionName} 下好了，点一下安装，数据都在" to colors.accent
        is Updates.State.Failed -> "${s.release.versionName} ${s.why}" to colors.red
        Updates.State.Latest -> "已是最新" to colors.muted
        else -> null to colors.muted
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
                    is Updates.State.Ready -> Updates.install(context, s.release)
                    is Updates.State.Downloading -> Unit
                    else -> Updates.check(context, force = true)
                }
            }
        }
    ) { Value(current, gap = false) }
}

/** 查得到、又没开的体检项，名字 */
private fun missingHealth(context: android.content.Context): List<String> =
    ShellEntries.health(context).filter { it.ok == false }.map { it.label }
