package com.abc.daodian.reminder.presentation.relay

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.relay.RelayMessage
import com.abc.daodian.reminder.relay.RelayStatus
import com.abc.daodian.reminder.relay.state
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.FixLink
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow

/**
 * 派活（试验版）：听哪个 app、听谁、暗号，底下是她发来的每一句和办成了什么。
 * 还没出设计稿，版式照设置页的纸凑的；验下来要留再按设计稿重画。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelayScreen(vm: RelayViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val messages by vm.messages.collectAsState()
    val seen by vm.seen.collectAsState()
    val connected by vm.connected.collectAsState()
    val apps by vm.apps.collectAsState()
    var granted by rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        granted = vm.granted()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("别人派的事", onBack) {
            if (messages.isNotEmpty()) {
                Text(
                    "清空记录", style = DaodianType.caption, color = colors.muted,
                    modifier = Modifier.clickable { vm.clear() }.padding(horizontal = 10.dp, vertical = 12.dp)
                )
            }
        }
        val s = settings ?: return@Column
        var who by rememberSaveable { mutableStateOf(s.who) }
        var code by rememberSaveable { mutableStateOf(s.code) }
        var trial by rememberSaveable { mutableStateOf("") }
        var showAll by rememberSaveable { mutableStateOf(false) }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues()
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Note(
                        "试验版。她在微信里给你发一句，这里接住，交给模型建成提醒。她那边什么都不用装。\n" +
                            "微信里要开「新消息通知 → 通知显示消息详情」；开着和她的聊天窗口时微信不发通知，那几句接不到。"
                    )
                    GroupLabel("通知监听")
                    PaperGroup {
                        if (!granted || !connected) {
                            SettingRow(
                                title = if (!granted) "没给通知使用权" else "监听没连着",
                                note = if (!granted) "和记账用的是同一个，去系统设置里打开「到点」"
                                else "去系统设置把「到点」的通知使用权关掉再打开",
                                noteColor = colors.red,
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                leading = { Marker(false) }
                            ) { FixLink() }
                        } else {
                            SettingRow(
                                title = "连着",
                                note = vm.sweepNote ?: "漏了的话点右边，把通知栏里挂着的再过一遍",
                                leading = { Marker(true) }
                            ) {
                                Text(
                                    "扫一遍", style = DaodianType.caption, color = colors.accent,
                                    modifier = Modifier.clickable { vm.sweep() }.padding(horizontal = 6.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                }
            }

            item(key = "apps-label") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    GroupLabel("听哪个 app · ${s.apps.size}")
                }
            }
            val list = apps
            if (list == null) {
                item(key = "apps-loading") { Note("在看手机上装了哪些 app……", Modifier.padding(horizontal = 20.dp)) }
            } else {
                val shown = if (showAll) list else list.filter { it.pkg in s.apps || vm.isChat(it.pkg) }
                item(key = "apps") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        PaperGroup {
                            shown.forEachIndexed { i, a ->
                                if (i > 0) GroupRule()
                                AppLine(a.label, checked = a.pkg in s.apps) { vm.setApp(a.pkg, it) }
                            }
                            if (!showAll) {
                                if (shown.isNotEmpty()) GroupRule()
                                SettingRow(title = "别的 app", note = "只列了聊天软件和短信", onClick = { showAll = true }) {
                                    ChevronRightIcon(size = 13.dp, tint = colors.muted)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Note("和记账勾的分开：这里勾上微信，记账不会因此存微信的通知。")
                    }
                }
            }

            item(key = "who") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    GroupLabel("听谁")
                    PaperGroup {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Field(who, "她在通知上的名字（微信备注名）") { who = it; vm.setWho(it) }
                            if (seen.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text("最近发过消息的，点一下填进去：", style = DaodianType.settingNote, color = colors.muted)
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    seen.forEach { name ->
                                        Chip(name, on = name == who) { who = name; vm.setWho(name) }
                                    }
                                }
                            } else if (s.apps.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Note("先在下面勾上微信，她再发一句（或者通知栏里挂着她的消息），这里就有名字可挑。")
                            }
                        }
                    }

                    GroupLabel("暗号")
                    PaperGroup {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Field(code, "比如「到点」") { code = it; vm.setCode(it) }
                            Spacer(Modifier.height(8.dp))
                            Note(
                                if (code.isBlank()) "没设：她发的每一句都交给模型，模型判断是不是要你做的事。"
                                else "只接「${code.trim()}」开头的，比如「${code.trim()} 明天下午三点取快递」。别的只记下，不发给模型。"
                            )
                        }
                    }

                    GroupLabel("试一句")
                    PaperGroup {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Field(trial, "假装她发来一句") { trial = it }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Note("不经微信，别的和真收到一样（暗号也看）。", Modifier.weight(1f))
                                Text(
                                    "当她发的", style = DaodianType.caption,
                                    color = if (trial.isBlank()) colors.hint else colors.accent,
                                    modifier = Modifier
                                        .clickable(enabled = trial.isNotBlank()) { vm.simulate(trial); trial = "" }
                                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                                )
                            }
                        }
                    }

                    GroupLabel("她发来的 · ${messages.size}")
                }
            }

            if (messages.isEmpty()) {
                item(key = "empty") {
                    Note("还没有。", Modifier.padding(horizontal = 20.dp))
                }
            } else {
                item(key = "log") {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                        PaperGroup {
                            messages.forEachIndexed { i, m ->
                                if (i > 0) GroupRule()
                                MessageLine(m, onOpen = onOpen, onRetry = { vm.retry(m.id) })
                            }
                        }
                    }
                }
            }

        }
    }
}

/** 一句：时刻 · 办成什么样，原话，模型怎么说 / 建了什么。建成的点进那条提醒 */
@Composable
private fun MessageLine(m: RelayMessage, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    val colors = DaodianColors.current
    val state = m.state
    val stuck = state == RelayStatus.WORKING && System.currentTimeMillis() - m.receivedAt > 2 * 60_000
    val label = when (state) {
        RelayStatus.SKIPPED -> m.detail ?: "只记下"
        RelayStatus.WORKING -> if (stuck) "没办完" else "在办"
        RelayStatus.CREATED -> "建了提醒"
        RelayStatus.NOT_TASK -> "不是待办"
        RelayStatus.FAILED -> "没办成"
    }
    val tone = when (state) {
        RelayStatus.CREATED -> colors.accent
        RelayStatus.FAILED -> colors.red
        else -> colors.muted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (state == RelayStatus.CREATED && m.reminderId != null) Modifier.clickable { onOpen(ReminderRoutes.edit(m.reminderId)) }
                else Modifier
            )
            .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Format.humanDateTimeShort(m.at) + (if (m.how == "test") " · 试的" else ""),
                style = DaodianType.caption, color = colors.hint, modifier = Modifier.weight(1f)
            )
            Text(label, style = DaodianType.caption, color = tone)
            if (state == RelayStatus.FAILED || stuck) {
                Text(
                    "再试", style = DaodianType.caption, color = colors.accent,
                    modifier = Modifier.clickable(onClick = onRetry).padding(start = 12.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(m.text, style = DaodianType.body, color = colors.ink)
        val detail = m.detail?.takeIf { state != RelayStatus.SKIPPED }
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail, style = DaodianType.settingNote, color = colors.muted, modifier = Modifier.weight(1f))
                if (state == RelayStatus.CREATED) ChevronRightIcon(size = 12.dp, tint = colors.muted)
            }
        }
    }
}

@Composable
private fun AppLine(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(start = 18.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = DaodianType.rowTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(20.dp).border(1.3.dp, if (checked) colors.accent else colors.rule2, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) CheckIcon(size = 13.dp, tint = colors.accent, strokeWidth = 1.6.dp)
        }
    }
}

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = DaodianType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.ink),
        modifier = Modifier.fillMaxWidth().height(44.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(colors.paper, shape).border(1.dp, colors.rule, shape).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, style = DaodianType.body, color = colors.hint, maxLines = 1)
                inner()
            }
        }
    )
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = DaodianType.caption,
        color = if (on) colors.accent else colors.ink2,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, if (on) colors.accent else colors.rule2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DaodianType.settingNote, color = DaodianColors.current.muted, modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp))
}
