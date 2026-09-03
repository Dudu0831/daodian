package com.abc.daodian.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.ChevronRightIcon
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import kotlinx.coroutines.launch

private val examplePrompts = listOf(
    "三分钟后提醒我喝水。",
    "下周三下午三点，交房租。",
    "每天早上八点提醒我吃药。",
    "这周五下班前把周报发出去。"
)

/** 对话页 —— app 主屏。见 DESIGN.md §02、daodian-ui-mockups 的 Main/ChatParsing/ChatCard/ChatClarify/ChatFailed */
@Composable
fun ChatScreen(
    vm: MainViewModel,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    onManualAdd: () -> Unit,
    onEditReminder: (Long) -> Unit
) {
    val colors = DaodianColors.current
    val messages by vm.messages.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val said = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!said.isNullOrBlank()) input = said
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send(text: String) {
        if (text.isBlank() || vm.aiBusy) return
        vm.sendMessage(text)
        input = ""
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {

        com.abc.daodian.ui.common.ChatTopBar(onOpenList = onOpenList, onOpenSettings = onOpenSettings)

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(onPickExample = { send(it) })
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        when (msg) {
                            is ChatMessage.UserText -> UserBubble(msg.text)
                            is ChatMessage.Thinking -> ThinkingRow()
                            is ChatMessage.AssistantText -> AssistantTextRow(
                                text = msg.text,
                                isError = msg.isError,
                                onManualAdd = if (msg.isError) onManualAdd else null
                            )
                            is ChatMessage.AssistantCard -> if (msg.collapsed) {
                                ReminderCardCollapsed(msg.plan)
                            } else {
                                ReminderCardExpanded(
                                    plan = msg.plan,
                                    nowMillis = System.currentTimeMillis(),
                                    onCollapse = { vm.collapseCard(msg.id) },
                                    onEdit = { onEditReminder(msg.reminderId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp).imePadding().navigationBarsPadding()) {
            ChatInputBar(
                text = input,
                onTextChange = { input = it },
                onSend = { send(input) },
                onMicClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    }
                    runCatching { voiceLauncher.launch(intent) }
                        .onFailure { if (it is ActivityNotFoundException) { /* 设备没有语音输入服务，安静忽略 */ } }
                },
                enabled = !vm.aiBusy,
                placeholder = if (vm.aiBusy) "解析中……" else if (messages.isEmpty()) "说点什么……" else "再说一句……"
            )
        }
    }
}

@Composable
private fun EmptyState(onPickExample: (String) -> Unit) {
    val colors = DaodianColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(22.dp))
        Text("下午好——", style = DaodianType.greetingItalic, color = colors.ink)
        Text("说说你想被提醒什么。", style = DaodianType.greetingBold, color = colors.ink)

        Spacer(Modifier.height(44.dp))
        Text("你可以这样说", style = DaodianType.monoSmall, color = colors.muted)

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = colors.rule)
        examplePrompts.forEach { prompt ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPickExample(prompt) }
                    .padding(vertical = 17.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(prompt, style = DaodianType.body, color = colors.ink2, modifier = Modifier.weight(1f))
                ChevronRightIcon(size = 15.dp, tint = colors.rule2)
            }
            HorizontalDivider(color = colors.rule)
        }
    }
}
