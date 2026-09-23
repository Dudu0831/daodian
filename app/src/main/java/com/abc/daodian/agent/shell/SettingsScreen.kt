package com.abc.daodian.agent.shell

import android.content.Intent
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
import com.abc.daodian.agent.feature.HealthItem
import com.abc.daodian.agent.model.provider.ApiState
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

/**
 * 设置 + 权限体检。见 DESIGN.md §08、§09.1
 *
 * 版式是一本账：顶上一句体检结论，底下一组一张纸、行间细线。壳只管框和自己的两组 ——
 * 「模型服务」「系统权限」（汇总各模块的 [com.abc.daodian.agent.feature.Feature.health]）；
 * 再往下是各模块自己的设置组（[FeatureUi.SettingsSection]）。
 *
 * 体检每次回到这页都重跑：从系统设置开完权限退回来，红字要当场变成对勾，
 * 不然会以为没开成功又去开一遍。
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

    var items by remember { mutableStateOf(FeatureRegistry.health(context)) }
    LifecycleResumeEffect(Unit) {
        items = FeatureRegistry.health(context)
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
            Verdict(items = items, uis = uis)

            // ---- 模型服务 ----
            // 改配置在配置页（顶栏那枚印点开也能到），这里只报个平安 + 一个去处。见决策 8.4
            GroupLabel("模型服务")
            PaperGroup {
                val down = api as? ApiState.Down
                SettingRow(
                    title = profile.model.ifBlank { "还没配置" },
                    note = when {
                        !profile.isConfigured -> "网关、key、模型有一项空着，说话办不了事"
                        down != null -> "上次没连上 · ${down.why}"
                        else -> hostOf(profile.baseUrl)
                    },
                    noteColor = if (down != null || !profile.isConfigured) colors.red else colors.muted,
                    titleColor = if (profile.model.isBlank()) colors.hint else colors.ink,
                    onClick = onOpenProvider
                ) { ChevronRightIcon(size = 13.dp, tint = colors.muted) }
            }

            // ---- 系统权限 ----
            GroupLabel("系统权限")
            PaperGroup {
                items.forEachIndexed { i, item ->
                    if (i > 0) GroupRule()
                    HealthRow(item, onFix = { launch(item.fixIntent) })
                }
            }

            uis.forEach { it.SettingsSection(open) }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 页头的一句结论：权限齐不齐。全好是墨字 + 朱砂小对勾；有缺口才用红字，写清楚缺几项、缺的是哪几项。
 * 查不到的（只能手动设的）不算。底下各模块可以补一行（[FeatureUi.HealthNote]）
 */
@Composable
private fun Verdict(items: List<HealthItem>, uis: List<FeatureUi>) {
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
 * 缺的那项才展开说后果，整行可点，右边一个「去开」。查不到的写清楚怎么手动设，右边一个「手动」
 */
@Composable
private fun HealthRow(item: HealthItem, onFix: () -> Unit) {
    val colors = DaodianColors.current
    if (item.ok == null) {
        SettingRow(title = item.label, note = item.detail, leading = { Marker(ok = null) }) { OutlineBadge("手动") }
        return
    }
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

/** 同纸签：地址只留主机名 */
private fun hostOf(baseUrl: String): String =
    baseUrl.substringAfter("://").substringBefore('/').trim()
