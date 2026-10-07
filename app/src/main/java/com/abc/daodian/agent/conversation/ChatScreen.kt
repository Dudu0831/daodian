package com.abc.daodian.agent.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
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
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.feature.PendingTrigger
import com.abc.daodian.shared.format.Format
import com.abc.daodian.agent.voice.VoiceInput
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 例句前的序号 */
private val ordinals = listOf("一", "二", "三", "四", "五", "六", "七", "八")

/** 流停了之后再贴底跟一小段：等卡片落印、下面几行错峰展开完 */
private const val FOLLOW_TAIL_NANOS = 900_000_000L

/** 发出去之后等键盘收起、先不贴底跟随，最多等这么久（荣耀上键盘收起那段 inset 动画四百来毫秒） */
private const val IME_HOLD_NANOS = 700_000_000L

/** 「按住说话」按得比这还短：当成点了一下，不录、不发，提示一句 */
private const val MIN_HOLD_MILLIS = 300L

/** 说话那一档，输入条上面那句提示（没听清、要按住）留多久 */
private const val VOICE_NOTE_MILLIS = 3000L

/** 松手之后最多等这么久；模型头一回加载要一两秒，得留够 */
private const val FINISH_TIMEOUT_MILLIS = 6000L

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
    val restored by vm.restored.collectAsState()
    // 问卡在等你：输入框照样能打字 —— 发出去是给问卡的（点了「其他…」只答那一题，否则算直接说）
    val asking = vm.asking
    val profile by vm.profile.collectAsState()
    val apiState by vm.apiState.collectAsState()
    // 通知弹了、还没对的每晚对账：对话末尾一段虚线。正在跑一轮时收起来 —— 这时候点了也开不了
    val waiting by vm.pendingTrigger.collectAsState()
    val pending = waiting.takeIf { !vm.aiBusy }
    var input by remember { mutableStateOf("") }
    // 历史读回来的那一刻换一个列表状态，直接停在最后一条（LazyColumn 会往回补满一屏）——
    // 不然先在最顶上画一帧，下一帧才被贴底跟随拉到底
    val listState = rememberSaveable(restored, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = if (restored) vm.messages.value.lastIndex.coerceAtLeast(0) else 0)
    }

    // 贴底跟随。一个回合在原地长大时条数不变，只盯条数的话新长出来的部分会掉到屏幕外
    var follow by remember { mutableStateOf(true) }
    // 键盘还开着就发出去了：到这个时刻之前、键盘没收完就先不跟（见 send）
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    var imeHoldUntil by remember { mutableLongStateOf(0L) }

    // 说话：和桌面速记同一套本地识别（VoiceInput，见 DESIGN.md 决策 8.3），收法不一样 ——
    // 点最左边的麦克风，输入框换成「按住说话」；按着说，松手这句直接发，按着上滑是取消（§8.3「对话页：按住说话」）
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val voice = remember { VoiceInput(context.applicationContext) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var hold by remember { mutableStateOf(VoiceHold.Idle) }
    // 松手了，最后几个字还在解：文字框先留着，解完才发
    var finishing by remember { mutableStateOf(false) }
    // 这一句不要了：文字框淡掉的那几帧照样画成虚线、划掉的字，别让人以为发出去了
    var dropped by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var holdStartedAt by remember { mutableLongStateOf(0L) }
    // 没听清 / 用不了：打字那一档顶替占位字，说话那一档写在输入条上面、过几秒自己收。没听清不是出错，不写红字
    var voiceNote by remember { mutableStateOf<String?>(null) }

    fun endHold() {
        hold = VoiceHold.Idle
        finishing = false
        level = 0f
    }

    fun cancelHold() {
        if (hold == VoiceHold.Idle && !finishing) return
        voice.cancel()
        dropped = true
        endHold()
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            focus.clearFocus()
            voiceMode = true
        } else {
            voiceNote = "没有麦克风权限，可以在系统设置里给「到点」打开"
        }
    }
    // 切到后台就不录了，说了一半的不发
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancelHold() }
    // 换到说话那一档就把模型加载上，第一次按下去不用等
    LaunchedEffect(voiceMode) { if (voiceMode) voice.warmUp() }
    LaunchedEffect(voiceNote, voiceMode) {
        if (voiceMode && voiceNote != null) {
            delay(VOICE_NOTE_MILLIS)
            voiceNote = null
        }
    }
    // 松手之后一般几百毫秒就解完。迟迟没回来（识别那边卡住了）就别让文字框一直挂着、也按不了下一句
    LaunchedEffect(finishing) {
        if (finishing) {
            delay(FINISH_TIMEOUT_MILLIS)
            cancelHold()
            voiceNote = "没听清，再说一次？"
        }
    }

    // 点了「停」：原话退回输入框，改两个字就能重发。正在说话那一档的话换回打字，不然看不见它
    LaunchedEffect(vm.restoredInput) {
        vm.restoredInput?.let {
            input = it
            voiceMode = false
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
    LaunchedEffect(follow, vm.aiBusy, messages.size, pending != null) {
        if (!follow || messages.isEmpty()) return@LaunchedEffect
        val tailUntil = System.nanoTime() + FOLLOW_TAIL_NANOS
        while (vm.aiBusy || System.nanoTime() < tailUntil) {
            withFrameNanos { }
            if (ime.getBottom(density) > 0 && System.nanoTime() < imeHoldUntil) continue
            val gap = listState.distanceToEnd()
            if (gap <= 0) continue
            try {
                listState.scrollBy(gap.toFloat())
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
                listState.scrollBy((if (follow) listState.distanceToEnd() else shrink).toFloat())
            } catch (e: CancellationException) {
                if (!isActive) throw e
            }
        }
    }

    /** [typed] = 发的是输入框里打的字，发完清空；说出来的那句不动输入框里还没发的草稿 */
    fun send(text: String, typed: Boolean = true) {
        if (text.isBlank()) return
        if (vm.aiBusy && asking == null) return
        // 发出去输入框就压暗、键盘跟着收（问卡在等时输入框还能打字，键盘不收）。气泡和墨条加在末尾、
        // 还压在输入框后面：这时贴底跟随会先把列表往上推，紧接着整页又跟着键盘往下落一大截，一上一下。
        // 等键盘收完再跟 —— 收的这一路它们自己从输入框后面露出来，列表只往下落
        if (asking == null && ime.getBottom(density) > 0) imeHoldUntil = System.nanoTime() + IME_HOLD_NANOS
        vm.sendMessage(text)
        if (typed) input = ""
        follow = true
    }

    fun beginHold() {
        if (hold != VoiceHold.Idle || finishing) return
        // 进这一档时问过权限了；中途在系统设置里收回去的，再问一次
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceNote = null
        heard = ""
        dropped = false
        hold = VoiceHold.Holding
        holdStartedAt = SystemClock.uptimeMillis()
        voice.start(hold = true) { event ->
            when (event) {
                VoiceInput.Event.Ready -> Unit
                is VoiceInput.Event.Partial -> heard = event.text
                is VoiceInput.Event.Level -> level = event.value
                // 松手之后解完了（或者按满了时长）：这句直接发，不落进输入框
                is VoiceInput.Event.Final -> {
                    val said = event.text.ifBlank { heard }
                    heard = said
                    endHold()
                    if (said.isBlank()) voiceNote = "没听清，再说一次？" else send(said, typed = false)
                }
                // 说到一半出错：半句话不替你发，听到的放进输入框，换回打字自己看着办
                is VoiceInput.Event.Error -> {
                    val partial = heard
                    endHold()
                    if (partial.isBlank()) {
                        voiceNote = event.message
                    } else {
                        input += partial
                        voiceMode = false
                    }
                }
            }
        }
    }

    fun releaseHold(cancelled: Boolean) {
        if (hold == VoiceHold.Idle) return
        when {
            cancelled -> cancelHold()
            // 点了一下就抬手：不是要发空话，是不知道得按住
            SystemClock.uptimeMillis() - holdStartedAt < MIN_HOLD_MILLIS -> {
                cancelHold()
                voiceNote = "按住说，松手就发"
            }
            else -> {
                hold = VoiceHold.Idle
                finishing = true
                voice.stop()
            }
        }
    }

    fun startPending(p: PendingTrigger) {
        cancelHold()
        follow = true
        vm.startTrigger(p.key)
    }

    // 挪位动画只给有条目进出的那一帧（发一句、喊停撤回、重试抹掉、对账那段虚线来去），下一帧就收回。
    // 库把「滚动」以外的位移全当挪位：键盘收放、输入框变高变矮、回合原地长大、贴底跟随滚得和实际差一点，
    // 都会让每一条拖在后面慢慢追 —— 键盘收起时整屏消息抖动变形就是这么来的。这些变化一律直接到位
    val itemKeys = remember(messages, pending?.key) { messages.map { it.id } + listOfNotNull(pending?.key) }
    var placedKeys by remember { mutableStateOf(itemKeys) }
    val placement: FiniteAnimationSpec<IntOffset>? = if (itemKeys != placedKeys) Motion.flow() else null
    SideEffect { placedKeys = itemKeys }

    val running by vm.running.collectAsState()
    val manualEntry = remember { FeatureRegistry.manualEntry }

    Column(Modifier.fillMaxSize().background(colors.paper)) {

        // 顶栏和对话包在一起：按住说话时那一层盖住它们俩，输入条留在外面
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {

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
                    // 历史还没读回来：中间先空着（开屏一般还盖着，见 MainActivity）。这时 messages 是空的，但不是没聊过 ——
                    // 画空状态的话，有记录的人会先看到招呼语闪一下
                    // 第一句话发出时，招呼语和例句淡出上移；「停」撤回最后一句、对话空了，它们再回来
                    if (restored) AnimatedContent(
                        targetState = messages.isEmpty(),
                        transitionSpec = {
                            fadeIn(Motion.flow()) togetherWith
                                (fadeOut(Motion.flow()) + slideOutVertically(Motion.flow()) { -it / 40 })
                        },
                        label = "emptyToChat"
                    ) { isEmpty ->
                        if (isEmpty) {
                            EmptyState(
                                onPickExample = { send(it) },
                                pending = pending,
                                onGo = ::startPending,
                                onDismiss = { vm.dismissTrigger(it.key) }
                            )
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
                                    Box(Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = Motion.exit())) {
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
                                pending?.let { p ->
                                    item(key = "pending:${p.key}") {
                                        Box(Modifier.animateItem(fadeInSpec = Motion.flow(), placementSpec = placement, fadeOutSpec = Motion.exit())) {
                                            PendingTriggerBlock(p, onGo = { startPending(p) }, onDismiss = { vm.dismissTrigger(p.key) })
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

            }

            // 松手那一下文字框不跟着没：等最后几个字解完，这句进了对话再淡掉
            androidx.compose.animation.AnimatedVisibility(
                visible = hold != VoiceHold.Idle || finishing,
                modifier = Modifier.matchParentSize(),
                enter = fadeIn(Motion.flow(Motion.SHORT)),
                exit = fadeOut(Motion.exit())
            ) {
                VoiceHoldOverlay(
                    heard = heard,
                    level = level,
                    armed = hold == VoiceHold.Armed || dropped,
                    pressed = hold != VoiceHold.Idle,
                    modifier = Modifier.fillMaxSize()
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
            AnimatedVisibility(
                visible = voiceMode && voiceNote != null && hold == VoiceHold.Idle,
                enter = fadeIn(Motion.flow(Motion.SHORT)) + expandVertically(Motion.flow(Motion.SHORT)),
                exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.flow(Motion.SHORT))
            ) {
                // 退场那几帧 voiceNote 已经是 null 了，用最后一次的字画完
                val last = remember { arrayOfNulls<String>(1) }
                voiceNote?.let { last[0] = it }
                Text(
                    last[0].orEmpty(),
                    style = DaodianType.caption,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                )
            }
            ChatInputBar(
                text = input,
                onTextChange = {
                    voiceNote = null
                    input = it
                },
                onSend = { send(input) },
                enabled = !vm.aiBusy || asking != null,
                voiceMode = voiceMode,
                onToggleVoice = {
                    voiceNote = null
                    when {
                        voiceMode -> voiceMode = false
                        !voice.available -> voiceNote = "这台手机没有可用的语音识别"
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                            focus.clearFocus()
                            voiceMode = true
                        }
                        else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                hold = hold,
                onHoldStart = ::beginHold,
                onHoldArm = { armed -> if (hold != VoiceHold.Idle) hold = if (armed) VoiceHold.Armed else VoiceHold.Holding },
                onHoldEnd = ::releaseHold,
                onStop = { vm.stopStreaming() },
                scope = vm.askScope,
                placeholder = when {
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
 * 按上一次排版，离贴底还差多少像素；最后一条还没排进来就先算一屏，下一帧接着滚。
 * 贴底不能 scrollBy 一个大数了事：这一帧内容要是变矮了（墨条收起、「在记账」那行换上来），列表实际得往回滚，
 * 库却把请求的整个数当成「滚过了」交给挪位动画 —— 每一条都被挪到一百万像素外再慢慢拉回来，整屏白一下
 */
private fun LazyListState.distanceToEnd(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < info.totalItemsCount - 1) return info.viewportSize.height
    return (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).coerceAtLeast(0)
}

/**
 * 空状态。招呼语跟时段走，底下四条例句是「这个 app 怎么用」的全部说明书 ——
 * 点一下就直接发出去，不用先学语法。
 * 有等着你点的一轮（[pending]）时，那段虚线压在最底下、贴着输入框，和有对话时的位置一样。
 */
@Composable
private fun EmptyState(
    onPickExample: (String) -> Unit,
    pending: PendingTrigger?,
    onGo: (PendingTrigger) -> Unit,
    onDismiss: (PendingTrigger) -> Unit
) {
    val colors = DaodianColors.current
    val greeting = remember { Format.greeting(LocalTime.now().hour) }

    // 键盘弹起时高度会砍掉一半，不给滚动的话例句会被裁掉两条。
    // 内容至少撑满一屏，虚线段前面那个 weight 才有空可占，把它推到底；键盘弹起、放不下时 weight 缩成 0，整页照常滚
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
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

            Spacer(Modifier.weight(1f))
            androidx.compose.animation.AnimatedVisibility(
                visible = pending != null,
                enter = fadeIn(Motion.flow()),
                exit = fadeOut(Motion.exit())
            ) {
                // 退场那几帧 pending 已经是 null 了，用最后一次的画完
                val last = remember { arrayOfNulls<PendingTrigger>(1) }
                pending?.let { last[0] = it }
                last[0]?.let { p ->
                    Box(Modifier.padding(bottom = 16.dp)) {
                        PendingTriggerBlock(p, onGo = { onGo(p) }, onDismiss = { onDismiss(p) })
                    }
                }
            }
        }
    }
}
