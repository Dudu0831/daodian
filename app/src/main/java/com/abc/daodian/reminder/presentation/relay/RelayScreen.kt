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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.relay.RelayMessage
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

/**
 * 她发来的（派活试验版）：试一句，底下是她发来的每一句和办成了什么。从提醒设置页「派活」那一组进。
 * 听谁、暗号在提醒设置页上改；通知使用权、听哪些 app 在「权限与监听」页 —— 这一页不放通知的行、不往那边跳。
 */
@Composable
fun RelayScreen(vm: RelayViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val colors = DaodianColors.current
    val messages by vm.messages.collectAsState()
    var trial by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("她发来的", onBack) {
            if (messages.isNotEmpty()) {
                Text(
                    "清空记录", style = DaodianType.caption, color = colors.muted,
                    modifier = Modifier.clickable { vm.clear() }.padding(horizontal = 10.dp, vertical = 12.dp)
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues()
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    GroupLabel("试一句", top = 12.dp)
                    PaperGroup {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            RelayField(trial, "假装她发来一句") { trial = it }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RelayNote("不经微信，别的和真收到一样（暗号也看）。", Modifier.weight(1f))
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
                    RelayNote(
                        "微信里要开「新消息通知 → 通知显示消息详情」；开着和她的聊天窗口时微信不发通知，那几句接不到。",
                        Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)
                    )

                    GroupLabel("她发来的 · ${messages.size}")
                }
            }

            if (messages.isEmpty()) {
                item(key = "empty") {
                    RelayNote("还没有。", Modifier.padding(horizontal = 20.dp))
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
