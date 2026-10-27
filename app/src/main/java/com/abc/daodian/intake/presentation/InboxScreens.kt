package com.abc.daodian.intake.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abc.daodian.intake.IntakeRoutes
import com.abc.daodian.intake.NoticeHub
import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.AppIn
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.rule.BodyHasAny
import com.abc.daodian.intake.core.rule.BodyHasNone
import com.abc.daodian.intake.core.rule.BodyStartsWith
import com.abc.daodian.intake.core.rule.Rule
import com.abc.daodian.intake.core.rule.TitleIs
import com.abc.daodian.intake.data.RuleOutcome
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.PaperSwitch
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import com.abc.daodian.shared.ui.UndoBar
import com.abc.daodian.shared.ui.activityViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * 听通知的四页（设计稿方向 B：https://claude.ai/artifact/Qg7TytQpTJd5N4CtuacTB8）：
 * 权限与监听 → 规则 → 一条规则；权限与监听 → 收到的通知 → 一条通知。
 * 规则只在对话里建、改，这里只看、停、删（DESIGN.md §12.2）。固定的说明字尽量不写
 */

/** 规则：在听的一组、停了的一组，一条一行。删了从详情页退回来，底下给 5 秒撤销 */
@Composable
fun RulesScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val vm = activityViewModel<InboxViewModel>()
    val colors = DaodianColors.current
    val items by vm.ruleItems.collectAsState()
    val deleted by vm.deleted.collectAsState()

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("规则", onBack)
            val list = items ?: return@Column
            LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                item(key = "rules") {
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 96.dp)) {
                        val (on, off) = list.partition { it.rule.enabled }
                        if (list.isEmpty()) Hint("还没有。在对话里说要听什么。", Modifier.padding(top = 12.dp))
                        if (on.isNotEmpty()) RuleGroup("在听", on, top = 12.dp, open)
                        if (off.isNotEmpty()) RuleGroup("停了", off, top = if (on.isEmpty()) 12.dp else 28.dp, open)
                    }
                }
            }
        }
        UndoBar(label = deleted?.let { "删了「${it.name}」" }, onUndo = vm::undoDelete, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RuleGroup(label: String, items: List<RuleItem>, top: Dp, open: (String) -> Unit) {
    val colors = DaodianColors.current
    GroupLabel(label, top = top)
    PaperGroup {
        items.forEachIndexed { i, item ->
            if (i > 0) GroupRule()
            val on = item.rule.enabled
            SettingRow(
                title = item.rule.name,
                note = "${item.describe} → ${item.rule.soft}",
                titleColor = if (on) colors.ink else colors.hint,
                onClick = { open(IntakeRoutes.rule(item.rule.id)) }
            ) {
                Text("${item.count} 条", style = DaodianType.caption, color = colors.muted)
                Spacer(Modifier.width(10.dp))
                ChevronRightIcon(size = 13.dp, tint = if (on) colors.muted else colors.hint)
            }
        }
    }
}

/** 一条规则：在听（开关）、听什么、进来之后、最近进来的、删掉 */
@Composable
fun RuleScreen(id: Long, onBack: () -> Unit, open: (String) -> Unit) {
    val vm = activityViewModel<InboxViewModel>()
    val colors = DaodianColors.current
    val items by vm.ruleItems.collectAsState()
    val entries by vm.entries.collectAsState()
    val item = items?.firstOrNull { it.rule.id == id }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(item?.rule?.name ?: "规则", onBack)
        // 还没读出来，或者刚删掉正往回退
        val rule = item?.rule ?: return@Column
        val mine = entries.orEmpty().filter { e -> e.rules.any { it.ruleId == id } }
        LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(12.dp))
                    PaperGroup {
                        SettingRow(title = "在听", onClick = { vm.setEnabled(rule, !rule.enabled) }) {
                            PaperSwitch(checked = rule.enabled, onCheckedChange = { vm.setEnabled(rule, it) })
                        }
                    }

                    GroupLabel("听什么")
                    PaperGroup {
                        conditionsOf(rule, vm.names).forEachIndexed { i, (k, v) ->
                            if (i > 0) GroupRule()
                            KeyValue(k, v)
                        }
                    }

                    GroupLabel("进来之后")
                    PaperGroup {
                        Text(rule.soft, style = DaodianType.body, color = colors.ink2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
                    }

                    GroupLabel("最近进来的 · ${mine.size}")
                    if (mine.isNotEmpty()) {
                        PaperGroup {
                            mine.forEachIndexed { i, e ->
                                if (i > 0) GroupRule()
                                EntryLine(e, onOpen = { open(IntakeRoutes.notice(e.row.id)) }, onRetry = vm::retry)
                            }
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    PaperGroup {
                        SettingRow(
                            title = "删掉这条规则",
                            titleColor = colors.red,
                            onClick = {
                                vm.delete(rule)
                                onBack()
                            }
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/** 收到的通知：按天分组，一条一行，底下写变成了什么 */
@Composable
fun NoticesScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val vm = activityViewModel<InboxViewModel>()
    val colors = DaodianColors.current
    val entries by vm.entries.collectAsState()

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("收到的通知", onBack)
        val list = entries ?: return@Column
        val zone = ZoneId.systemDefault()
        val days = list.groupBy { Instant.ofEpochMilli(it.row.receivedAt).atZone(zone).toLocalDate() }
        LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            if (list.isEmpty()) item(key = "empty") { Hint("还没有。", Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
            days.entries.forEachIndexed { d, (day, es) ->
                item(key = day.toString()) {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        GroupLabel(dayLabel(day), top = if (d == 0) 12.dp else 28.dp)
                        PaperGroup {
                            es.forEachIndexed { i, e ->
                                if (i > 0) GroupRule()
                                EntryLine(e, onOpen = { open(IntakeRoutes.notice(e.row.id)) }, onRetry = vm::retry, clockOnly = true)
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** 一条通知：全文、中了哪几条规则各怎么定的、交给谁办成了什么 */
@Composable
fun NoticeScreen(id: Long, onBack: () -> Unit, open: (String) -> Unit) {
    val vm = activityViewModel<InboxViewModel>()
    val colors = DaodianColors.current
    val entries by vm.entries.collectAsState()
    val ruleNames by vm.ruleNames.collectAsState()
    val e = entries?.firstOrNull { it.row.id == id }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(e?.let { titleOf(it) } ?: "通知", onBack)
        // 还没读出来，或者办完 30 天被清掉了
        if (e == null) return@Column
        LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item(key = "body") {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                    Text(
                        "${e.app} · ${Format.humanDateTimeShort(e.row.at)}",
                        style = DaodianType.caption, color = colors.muted,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 10.dp)
                    )
                    PaperGroup {
                        Text(
                            fullText(e),
                            style = DaodianType.body, color = colors.ink,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                        )
                    }

                    GroupLabel("中了的规则")
                    PaperGroup {
                        e.rules.forEachIndexed { i, r ->
                            if (i > 0) GroupRule()
                            val name = ruleNames[r.ruleId]
                            SettingRow(
                                title = name ?: "r${r.ruleId}（删了）",
                                note = when (r.outcome) {
                                    RuleOutcome.HANDED -> "交给" + r.targets.orEmpty().split(',').filter { it.isNotBlank() }
                                        .joinToString("、") { NoticeHub.label(it) }
                                    RuleOutcome.IGNORED -> listOf("不相关").plus(listOfNotNull(r.why)).joinToString(" · ")
                                    null -> "还没定"
                                },
                                titleColor = if (name != null) colors.ink else colors.hint,
                                onClick = name?.let { { open(IntakeRoutes.rule(r.ruleId)) } }
                            ) {
                                if (name != null) ChevronRightIcon(size = 13.dp, tint = colors.muted)
                            }
                        }
                    }

                    if (e.handoffs.isNotEmpty()) {
                        GroupLabel("办成了什么")
                        PaperGroup {
                            e.handoffs.forEachIndexed { i, h ->
                                if (i > 0) GroupRule()
                                val o = handoffOutcome(h)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .then(if (h.route != null) Modifier.clickable { open(h.route) } else Modifier)
                                        .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(NoticeHub.label(h.target), style = DaodianType.rowTitle, color = colors.ink, modifier = Modifier.weight(1f))
                                        if (h.route != null) ChevronRightIcon(size = 13.dp, tint = colors.muted)
                                    }
                                    OutcomeText(o.copy(text = o.text.substringAfter(" · ")), onRetry = vm::retry)
                                    if (h.note.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("附言：${h.note}", style = DaodianType.settingNote, color = colors.muted)
                                    }
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                        e.outcomes.forEach { OutcomeText(it, onRetry = vm::retry, modifier = Modifier.padding(horizontal = 4.dp)) }
                    }
                }
            }
        }
    }
}

/** 记录的一行：几点 · 哪个 app、标题、正文开头、变成了什么 */
@Composable
private fun EntryLine(e: Entry, onOpen: () -> Unit, onRetry: (Outcome) -> Unit, clockOnly: Boolean = false) {
    val colors = DaodianColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        val time = if (clockOnly) Format.clock(e.row.at) else Format.humanDateTimeShort(e.row.at)
        Text("$time · ${e.app}", style = DaodianType.caption, color = colors.muted)
        Spacer(Modifier.height(3.dp))
        Text(titleOf(e), style = DaodianType.rowTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            bodyOf(e), style = DaodianType.bodySmall, color = colors.ink2,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
        )
        e.outcomes.forEach { OutcomeText(it, onRetry) }
    }
}

@Composable
private fun OutcomeText(o: Outcome, onRetry: (Outcome) -> Unit, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    val tone: Color = when (o.tone) {
        Tone.DONE -> colors.accent
        Tone.QUIET -> colors.hint
        Tone.WAIT -> colors.amber
        Tone.BAD -> colors.red
    }
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(o.text, style = DaodianType.caption, color = tone, modifier = Modifier.weight(1f, fill = false))
        if (o.retryNotice != null || o.retryHandoff != null) {
            Text(
                "再试", style = DaodianType.caption, color = colors.accent,
                modifier = Modifier.clickable { onRetry(o) }.padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 13.dp, bottom = 13.dp)) {
        Text(key, style = DaodianType.caption, color = colors.muted, modifier = Modifier.width(56.dp).padding(top = 2.dp))
        Text(value, style = DaodianType.body, color = colors.ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DaodianType.settingNote, color = DaodianColors.current.hint, modifier = modifier)
}

/** 硬条件一样一行：「app：短信、菜鸟」「正文含：取件码 / 驿站」 */
private fun conditionsOf(rule: Rule, names: AppNames): List<Pair<String, String>> = rule.hard.parts.mapNotNull { c ->
    when (c) {
        is AppIn -> "app" to c.pkgs.joinToString("、") { names.of(it) }
        is TitleIs -> "标题" to if (c.exact) "是「${c.text}」" else "含「${c.text}」"
        is BodyHasAny -> "正文含" to c.words.joinToString(" / ")
        is BodyHasNone -> "不含" to c.words.joinToString(" / ")
        is BodyStartsWith -> "暗号" to "「${c.code}」开头"
        is AllOf -> null
    }
}

private fun titleOf(e: Entry): String = e.row.normTitle?.takeIf { it.isNotBlank() } ?: e.app

private fun bodyOf(e: Entry): String =
    if (e.row.redacted) "（被系统遮蔽了）" else e.row.normBody?.takeIf { it.isNotBlank() } ?: e.row.text.orEmpty()

private fun fullText(e: Entry): String {
    if (e.row.redacted) return "（被系统遮蔽了，看不清）"
    return listOfNotNull(e.row.normBody?.takeIf { it.isNotBlank() } ?: e.row.text, e.row.extra).joinToString("\n")
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> Format.humanDay(day)
    }
}
