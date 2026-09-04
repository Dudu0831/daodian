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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.ChatTopBar
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import java.time.LocalTime

private val examplePrompts = listOf(
    "一" to "三分钟后提醒我喝水。",
    "二" to "下周三下午三点，交房租。",
    "三" to "每天早上八点提醒我吃药。",
    "四" to "这周五下班前把周报发出去。"
)

/** 对话页 —— app 主屏。见 DESIGN.md §08 界面，视觉稿 Main / Parsing / Clarify / Failed 四块画板 */
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

        ChatTopBar(onOpenList = onOpenList, onOpenSettings = onOpenSettings)

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(onPickExample = { send(it) })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    // 对话从底下往上长 —— 内容少的时候贴着输入框，不要飘在屏幕顶上
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        when (msg) {
                            is ChatMessage.UserText -> UserBubble(msg.text)
                            is ChatMessage.Thinking -> ThinkingRow()
                            is ChatMessage.AssistantText -> AssistantTextRow(
                                text = msg.text,
                                isError = msg.isError,
                                onManualAdd = if (msg.isError) onManualAdd else null,
                                onRetry = if (msg.isError) ({ vm.retryLast() }) else null
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

        Column(
            Modifier
                // ime 和导航栏取并集，不能各 padding 一遍 —— 键盘弹起时导航栏本来就被键盘盖住了，
                // 两个都加会把输入框顶高一截。
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp)
        ) {
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
                placeholder = when {
                    vm.aiBusy -> "解析中……"
                    messages.isEmpty() -> "说一句话……"
                    else -> "再说点什么……"
                }
            )
        }
    }
}

/**
 * 空状态。招呼语跟时段走，底下四条例句是「这个 app 怎么用」的全部说明书 ——
 * 点一下就直接发出去，不用先学语法。
 */
@Composable
private fun EmptyState(onPickExample: (String) -> Unit) {
    val colors = DaodianColors.current
    val greeting = remember { Format.greeting(LocalTime.now().hour) }

    // 键盘弹起时高度会砍掉一半，不给滚动的话例句会被裁掉两条
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(96.dp))
        Text(greeting, style = DaodianType.greetingSoft, color = colors.muted)
        Text("有什么要记着的？", style = DaodianType.greeting, color = colors.ink)

        Spacer(Modifier.height(52.dp))
        Text("这样说就行", style = DaodianType.sectionLabel, color = colors.muted)
        Spacer(Modifier.height(6.dp))

        examplePrompts.forEach { (ordinal, prompt) ->
            HorizontalDivider(color = colors.rule)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPickExample(prompt) }
                    .padding(vertical = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(ordinal, style = DaodianType.ordinal, color = colors.hint)
                Text(prompt, style = DaodianType.body, color = colors.ink2)
            }
        }
        HorizontalDivider(color = colors.rule)
        Spacer(Modifier.height(24.dp))
    }
}
