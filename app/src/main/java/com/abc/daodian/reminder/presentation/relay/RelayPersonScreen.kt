package com.abc.daodian.reminder.presentation.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.relay.RelayMessage
import com.abc.daodian.reminder.relay.RelaySettings.Person
import com.abc.daodian.reminder.relay.RelayStatus
import com.abc.daodian.reminder.relay.state
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

/**
 * 派活名单上某个人的那一页（设计稿方向 C「一人一页」：https://claude.ai/artifact/AbLfPQLYAAGQGY3LNUF37D）：
 * 怎么接（名字、暗号、不听了），底下是这个人发来的每一句和办成了什么。从提醒设置页「派活」那一组点人进。
 * 通知使用权、听哪些 app 在「权限与监听」页 —— 这一页不放通知的行、不往那边跳。
 */
@Composable
fun RelayPersonScreen(personId: Long, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val vm = activityViewModel<RelayViewModel>()
    val colors = DaodianColors.current
    val people by vm.people.collectAsState()
    val counts by vm.counts.collectAsState()
    val seen by vm.seen.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var coding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    // 点了「不听了」往回退的那一小段，名单上已经没有这个人了：接着画退之前的样子，别闪成空页
    var last by remember { mutableStateOf<Person?>(null) }
    val found = people?.firstOrNull { it.id == personId }
    if (found != null) last = found
    val person = found ?: last

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        if (person == null) {
            // 名单还没读出来，或者这个人早就不在名单上了（旧通知点进来的）
            ScreenTopBar("派活", onBack)
            return@Column
        }
        val messages by remember(person.name) { vm.messagesOf(person.name) }.collectAsState(initial = null)
        val list = messages

        ScreenTopBar(person.name, onBack) {
            if (!list.isNullOrEmpty()) {
                Text(
                    "清空记录", style = DaodianType.caption, color = colors.muted,
                    modifier = Modifier.clickable { vm.clear(person) }.padding(horizontal = 10.dp, vertical = 12.dp)
                )
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    GroupLabel("怎么接", top = 12.dp)
                    PaperGroup {
                        SettingRow(
                            title = "名字",
                            note = "通知标题上的名字；微信是你给的备注名。",
                            onClick = { renaming = true }
                        ) { Value(person.name) }
                        GroupRule()
                        SettingRow(
                            title = "暗号",
                            note = "设了只接这几个字开头的；空着句句都交给模型。",
                            onClick = { coding = true }
                        ) { Value(person.code) }
                        GroupRule()
                        val n = counts[person.name] ?: 0
                        SettingRow(
                            title = "不听${person.name}了",
                            note = if (n > 0) "连发来的 $n 句记录一起删掉。" else "从名单上拿掉。",
                            titleColor = colors.red,
                            // 点两下只算一下：第二下会把留给撤销的记录换成空的，还多退一层
                            onClick = {
                                if (!leaving) {
                                    leaving = true
                                    vm.forget(person)
                                    onBack()
                                }
                            }
                        )
                    }
                    RelayNote(
                        "微信里要开「新消息通知 → 通知显示消息详情」；开着和对方的聊天窗口时微信不发通知，那几句接不到。",
                        Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)
                    )

                    GroupLabel("${person.name}发来的" + (list?.let { " · ${it.size}" } ?: ""))
                }
            }

            if (list != null && list.isEmpty()) {
                item(key = "empty") {
                    RelayNote("还没有。", Modifier.padding(horizontal = 24.dp))
                }
            } else if (list != null) {
                item(key = "log") {
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        PaperGroup {
                            list.forEachIndexed { i, m ->
                                if (i > 0) GroupRule()
                                MessageLine(m, onOpen = onOpen, onRetry = { vm.retry(m.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (person != null && renaming) {
        RelayNameDialog(
            current = person.name,
            seen = seen.filter { s -> people.orEmpty().none { it.name == s } },
            onSave = { name -> vm.rename(person, name).also { if (it == null) renaming = false } },
            onDismiss = { renaming = false }
        )
    }
    if (person != null && coding) {
        RelayCodeDialog(
            who = person.name,
            current = person.code,
            onSave = { vm.setCode(person, it); coding = false },
            onDismiss = { coding = false }
        )
    }
}

/** 行右边的值；空着写淡淡的「没设」。名字长了截掉，别把左边的标题挤没 */
@Composable
private fun Value(text: String) {
    val colors = DaodianColors.current
    Text(
        text.ifBlank { "没设" },
        style = DaodianType.settingValue,
        color = if (text.isBlank()) colors.hint else colors.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 160.dp)
    )
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
                // 「试的」是以前「试一句」留下的记录，不是真发来的
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
