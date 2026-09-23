package com.abc.daodian.agent.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.shared.format.Format
import com.abc.daodian.agent.voice.VoiceInput
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

/** 例句前的序号 */
private val ordinals = listOf("一", "二", "三", "四", "五", "六", "七", "八")

/** 流停了之后再贴底跟一小段：等卡片落印、下面几行错峰展开完 */
private const val FOLLOW_TAIL_NANOS = 900_000_000L

/**
 * 对话页 —— app 主屏。见 DESIGN.md §08 界面，视觉稿 Main / Parsing / Clarify / Failed 四块画板
 *
 * 抽屉在外面由壳套上（agent/shell/AppNavHost）。点痕、「手动填一条」都是去某个模块的页面，只给路由（[onOpen]）。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenProvider: () -> Unit,
    onOpen: (String) -> Unit
) {
    val colors = DaodianColors.current
    val messages by vm.messages.collectAsState()
    // 问卡在等你：输入框照样能打字 —— 发出去是给问卡的（点了「其他…」只答那一题，否则算直接说）
    val asking = vm.asking
    val profile by vm.profile.collectAsState()
    val apiState by vm.apiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 贴底跟随。一个回合在原地长大时条数不变，只盯条数的话新长出来的部分会掉到屏幕外
    var follow by remember { mutableStateOf(true) }

    // 麦克风：和桌面速记同一套本地识别（VoiceInput，见 DESIGN.md 决策 8.3）。
    // 边说边把字写进输入框，接在已经打了的字后面；说完不自动发 —— 这里是能改字的地方，改好了自己按发送
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val voice = remember { VoiceInput(context.applicationContext) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    // 没听清 / 用不了：顶替占位字，一打字或再点麦克风就没了。没听清不是出错，不写红字
    var voiceNote by remember { mutableStateOf<String?>(null) }

    fun endListening() {
        listening = false
        level = 0f
    }

    fun startListening() {
        val before = input
        focus.clearFocus()
        voiceNote = null
        listening = true
        voice.start { event ->
            when (event) {
                VoiceInput.Event.Ready -> Unit
                is VoiceInput.Event.Partial -> input = before + event.text
                is VoiceInput.Event.Level -> level = event.value
                is VoiceInput.Event.Final -> {
                    endListening()
                    if (event.text.isNotBlank()) input = before + event.text
                    else if (input == before) voiceNote = "没听清，再说一次？"
                }
                // 说到一半出错：已经写进框里的字留着
                is VoiceInput.Event.Error -> {
                    endListening()
                    if (input == before) voiceNote = event.message
                }
            }
        }
    }

    fun cancelListening() {
        if (!listening) return
        voice.cancel()
        endListening()
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else voiceNote = "没有麦克风权限，可以在系统设置里给「到点」打开"
    }
    // 切到后台就不录了
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancelListening() }

    // 点了「停」：原话退回输入框，改两个字就能重发
    LaunchedEffect(vm.restoredInput) {
        vm.restoredInput?.let {
            input = it
            vm.consumeRestoredInput()
        }
    }

    // 你一动手拖列表就不再跟；停下来时离底部多远，决定要不要接着跟
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(follow, vm.aiBusy, messages.size) {
        if (!follow || messages.isEmpty()) return@LaunchedEffect
        val tailUntil = System.nanoTime() + FOLLOW_TAIL_NANOS
        while (vm.aiBusy || System.nanoTime() < tailUntil) {
            withFrameNanos { }
            if (!listState.canScrollForward) continue
            try {
                listState.scrollBy(1_000_000f)
            } catch (e: CancellationException) {
                // 你的手指优先级更高，会把这一下挤掉 —— 只有这个协程本身被取消才往外抛
                if (!isActive) throw e
            }
        }
    }

    // 键盘弹起、授权条长出来，列表都是从底下被压矮的：LazyColumn 默认钉住顶上，最新那几句会被压到输入框后面。
    // 矮了多少就往下滚多少，底边的内容跟着输入框一起往上走；本来贴着底的直接滚到底。
    // 变高不用管 —— 已经到底的话列表自己会往回补
    LaunchedEffect(listState) {
        var lastHeight = 0
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { height ->
            val shrink = lastHeight - height
            lastHeight = height
            if (shrink <= 0 || height == 0) return@collect
            try {
                listState.scrollBy(if (follow) 1_000_000f else shrink.toFloat())
            } catch (e: CancellationException) {
                if (!isActive) throw e
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        if (vm.aiBusy && asking == null) return
        vm.sendMessage(text)
        cancelListening()
        input = ""
        follow = true
    }

    val running by vm.running.collectAsState()
    val manualEntry = remember { FeatureRegistry.manualEntry }

    Column(Modifier.fillMaxSize().background(colors.paper)) {

        ChatTopBar(
            profile = profile,
            api = apiState,
            running = running,
            onOpenDrawer = {
                focus.clearFocus()
                onOpenDrawer()
            },
            onOpenProvider = onOpenProvider
        )

        Box(Modifier.weight(1f)) {
            // 第一句话发出时，招呼语和例句淡出上移；「停」撤回最后一句、对话空了，它们再回来
            AnimatedContent(
                targetState = messages.isEmpty(),
                transitionSpec = {
                    fadeIn(Motion.flow()) togetherWith
                        (fadeOut(Motion.flow()) + slideOutVertically(Motion.flow()) { -it / 40 })
                },
                label = "emptyToChat"
            ) { isEmpty ->
                if (isEmpty) {
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
                            // 进场各自有戏（气泡升起、回合展开），这里只管挪位和退场
                            Box(Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.flow(), fadeOutSpec = Motion.exit())) {
                                when (msg) {
                                    is ChatMessage.UserText -> if (msg.trigger) TriggerDivider(msg) else UserBubble(msg)
                                    is ChatMessage.AssistantTurn -> {
                                        val actions = remember(msg.id) {
                                            object : TurnActions {
                                                override fun toggleReasoning() = vm.toggleReasoning(msg.id)
                                                override fun toggleTrace(callId: String) = vm.toggleTrace(msg.id, callId)
                                                override fun openTrace(route: String) = onOpen(route)
                                                override fun pick(callId: String, question: Int, option: Int) = vm.pickAsk(callId, question, option)
                                                override fun other(callId: String, question: Int) = vm.otherAsk(callId, question)
                                                override fun submit(callId: String) = vm.submitAsk(callId)
                                                override fun manualAdd() {
                                                    manualEntry?.let(onOpen)
                                                }
                                                override fun retry() = vm.retryLast()
                                            }
                                        }
                                        AssistantTurnRow(msg, actions)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 写全包名：外层有 Column，不写的话会解析成 ColumnScope 版本，DSL 作用域不让这么调
            androidx.compose.animation.AnimatedVisibility(
                visible = !follow && vm.aiBusy && asking == null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                enter = fadeIn(Motion.flow()) + slideInVertically(Motion.settle()) { it / 2 },
                exit = fadeOut(Motion.exit())
            ) {
                val shape = RoundedCornerShape(14.dp)
                Text(
                    "↓ 新内容",
                    style = DaodianType.caption,
                    color = colors.ink2,
                    modifier = Modifier
                        .background(colors.surface, shape)
                        .border(1.dp, colors.rule, shape)
                        .clickable { follow = true }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
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
                onTextChange = {
                    // 自己动手改字了，就不再往里写听到的
                    cancelListening()
                    voiceNote = null
                    input = it
                },
                onSend = { send(input) },
                onMicClick = {
                    when {
                        listening -> voice.stop()
                        !voice.available -> voiceNote = "这台手机没有可用的语音识别"
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                            startListening()
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                listening = listening,
                level = level,
                enabled = !vm.aiBusy || asking != null,
                onStop = { vm.stopStreaming() },
                scope = vm.askScope,
                placeholder = when {
                    listening -> "在听，说吧——"
                    voiceNote != null -> voiceNote.orEmpty()
                    vm.askScope != null -> "说是什么……"
                    asking != null -> if (asking.single) "都不是？直接说……" else "或者直接说……"
                    vm.aiBusy -> "正在说……"
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

        FeatureRegistry.examples.zip(ordinals).forEach { (prompt, ordinal) ->
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
