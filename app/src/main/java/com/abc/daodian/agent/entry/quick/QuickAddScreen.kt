package com.abc.daodian.agent.entry.quick

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.agent.conversation.AssistantTurnRow
import com.abc.daodian.agent.conversation.InkText
import com.abc.daodian.agent.conversation.PillButton
import com.abc.daodian.agent.conversation.PillStyle
import com.abc.daodian.agent.conversation.TurnActions
import com.abc.daodian.shared.ui.MicIcon
import com.abc.daodian.shared.ui.StopIcon
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 印落下之后停多久自己缩回去：够看清「已记下」和时间，又不至于让人干等。纸底边那根细线就是它 */
private const val AUTO_CLOSE_MS = 2_600

/**
 * 桌面速记那一张纸。见 DESIGN.md §8.3，交互稿：
 * https://claude.ai/code/artifact/14ee235b-dafd-45ff-9a43-9e11cd16d8ce
 *
 * 纸外面**什么都不蒙** —— 桌面原样，纸只靠自己的投影浮起来，点纸外面＝取消。
 * （上一版铺过 72% 的宣纸色压暗层，真机上等于把整个桌面洗白了。）
 *
 * 纸从整块小组件里长出来（容器变换）：纸框一开始就是小组件那一块（同样的系统圆角），
 * 底边钉住，往上、往两边长成整张纸；记完再缩回小组件。三件事叠在一起才读得出「是小组件在变大」：
 * 纸框用 [Motion.Expand] 伸展；纸色在头两成时间里盖住小组件上的字；小组件右下角那枚墨印
 * 跟着一路飞到纸中间、放大成纸上的墨印 —— 它是前后唯一没断过的东西。
 * 回合和对话页长得一模一样（同一个 [AssistantTurnRow]）。
 */
@Composable
fun QuickAddScreen(
    vm: QuickAddViewModel,
    anchor: Rect?,
    anchorCorner: Float,
    mic: Rect?,
    onVoice: () -> Unit,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onManual: () -> Unit,
    onOpenApp: () -> Unit,
    onHandoff: (String) -> Unit
) {
    val colors = DaodianColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 展开的进度，按**时间**线性走 0 → 1。纸框伸展、纸色、字、飞着的墨印各自从它换算自己的曲线 ——
    // 拿缓动后的值去切「纸色头两成渐显」的话，前两成在二十毫秒里就走完了，看着就是一下蹦出来
    val grow = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val slow = remember { QuickTrace.slowMo(context) }
    LaunchedEffect(Unit) {
        val t0 = System.nanoTime()
        QuickTrace.log(context, "screen grow start slow=$slow")
        grow.animateTo(1f, tween(Motion.EXPAND * slow, easing = LinearEasing))
        QuickTrace.log(context, "screen grow done in ${(System.nanoTime() - t0) / 1_000_000}ms")
    }
    // 调试：前 30 帧花了多久。纸长不出来时，用它分清是动画没跑，还是窗口压根没在出帧
    LaunchedEffect(Unit) {
        val t0 = System.nanoTime()
        repeat(30) { withFrameNanos { } }
        QuickTrace.log(context, "screen 30 frames in ${(System.nanoTime() - t0) / 1_000_000}ms")
    }

    // 退场：先把收音和流停掉，纸缩回小组件之后再真的关窗
    val leave: (String) -> Unit = { reason ->
        if (!leaving) {
            QuickTrace.log(context, "screen leave: $reason")
            leaving = true
            vm.stopAll()
            scope.launch {
                grow.animateTo(0f, tween(Motion.COLLAPSE * slow, easing = LinearEasing))
                onClose()
            }
        }
    }
    BackHandler { leave("back") }

    // 模型要出问卡：这张纸放不下，交给对话页
    LaunchedEffect(vm.handoff) { vm.handoff?.let(onHandoff) }

    // 落印之后用户碰了纸，说明还想看 / 还想改 —— 不再自己走
    var held by remember { mutableStateOf(false) }
    val countdown = remember { Animatable(1f) }
    LaunchedEffect(vm.saved, held) {
        if (vm.saved && !held) {
            countdown.snapTo(1f)
            countdown.animateTo(0f, tween(AUTO_CLOSE_MS, easing = LinearEasing))
            leave("auto")
        }
    }

    val margin = with(density) { 10.dp.roundToPx() }
    val topInset = WindowInsets.statusBars.getTop(density)
    val bottomInset = WindowInsets.navigationBars.getBottom(density)
    val corner = with(density) { 22.dp.toPx() }
    val shadow = colors.ink.copy(alpha = 0.45f)
    // 纸在窗口里的纵坐标。排版时写、画外形时读 —— 墨印的位置要换算成纸自己的坐标
    val sheetY = remember { mutableIntStateOf(0) }
    val sheetX = remember { mutableIntStateOf(margin) }
    // 纸上那枚墨印落定后在窗口里的位置。展开途中，小组件上的墨印从右下角一路飞到这里、放大成它
    var sealBounds by remember { mutableStateOf<Rect?>(null) }
    // 飞行中：纸上的印先藏着，由飞着的那枚代替。纸已经落定、或者纸上已经没有印（落印之后、语音用不了）就不飞
    val flying = mic != null && sealBounds != null && grow.value < 1f && !vm.saved && vm.blocked == null

    Box(Modifier.fillMaxSize()) {
        // 纸外面：不蒙任何颜色，只接「点一下＝取消」
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { leave("tap outside at $it") } })

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Column(
                    Modifier
                        .graphicsLayer {
                            val e = Motion.Expand.transform(grow.value)
                            shape = RevealShape(e, anchor?.translate(-sheetX.intValue.toFloat(), -sheetY.intValue.toFloat()), anchorCorner, corner)
                            clip = true
                            // 投影等纸色盖实了再出：纸还半透明时，投影会从纸底下透上来，框里一圈灰
                            shadowElevation = 18.dp.toPx() * e * ((grow.value - 0.2f) / 0.3f).coerceIn(0f, 1f)
                            ambientShadowColor = shadow
                            spotShadowColor = shadow
                        }
                        // 纸自己接住触摸（纸外面那层就收不到了），顺便记下落印之后有没有被碰过
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                if (vm.saved) held = true
                            }
                        }
                        // 纸色在头两成时间里渐显：底下小组件的字被纸盖住，而不是纸一出现就全没了
                        .drawBehind { drawRect(colors.paper.copy(alpha = (grow.value / 0.2f).coerceIn(0f, 1f))) }
                        .drawWithContent {
                            drawContent()
                            // 自动收起的倒计时：底边一根细线走完，纸就缩回去
                            if (vm.saved && !held) {
                                val h = 2.dp.toPx()
                                drawRect(
                                    colors.ink2.copy(alpha = 0.28f),
                                    topLeft = Offset(0f, size.height - h),
                                    size = Size(size.width * countdown.value, h)
                                )
                            }
                        }
                        .border(1.dp, colors.rule, RoundedCornerShape(22.dp))
                        .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)
                        // 纸上的字等纸框长开了再淡进来（三成到八成时间），不然会看见字挤在小组件那一块里。
                        // 收起时反过来：纸框还没怎么缩，字在头四分之一段就先走了
                        .graphicsLayer {
                            alpha = if (leaving) ((grow.value - 0.75f) / 0.25f).coerceIn(0f, 1f)
                            else ((grow.value - 0.3f) / 0.5f).coerceIn(0f, 1f)
                        }
                        .animateContentSize(Motion.flow(Motion.LONG)),
                    // 小组件比纸上的内容还高时（3×3、4×4），纸按小组件的框铺开，内容居中放
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    SheetContent(
                        vm, onVoice, onOpen, onManual, onOpenApp,
                        sealFaceVisible = !flying,
                        onSealPlaced = { sealBounds = it }
                    )
                }
            }
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            // 纸至少和小组件一样大：拖成 4 格宽、3×3 / 4×4 的小组件本来就比纸的默认尺寸大，
            // 纸从它里面「长」出来时不能反倒缩一圈。宽盖住整块，高不低于整块
            val left = anchor?.let { minOf(margin, it.left.roundToInt().coerceAtLeast(0)) } ?: margin
            val right = anchor?.let { maxOf(w - margin, it.right.roundToInt().coerceAtMost(w)) } ?: (w - margin)
            val sheetW = (right - left).coerceAtLeast(0)
            val maxH = (h - topInset - bottomInset - 2 * margin).coerceAtLeast(0)
            val minH = anchor?.height?.roundToInt()?.coerceIn(0, maxH) ?: 0
            val sheet = measurables.first().measure(
                Constraints(minWidth = sheetW, maxWidth = sheetW, minHeight = minH, maxHeight = maxH)
            )
            // 底边和小组件底边齐、往上长；顶到状态栏了才往下让
            val floor = h - bottomInset - margin
            val wantBottom = anchor?.let { it.bottom.roundToInt().coerceAtMost(floor) } ?: floor
            val y = (wantBottom - sheet.height).coerceAtLeast(topInset + margin)
            if (y != sheetY.intValue) {
                QuickTrace.log(context, "screen place y=$y h=${sheet.height} w=$w wantBottom=$wantBottom anchor=$anchor")
            }
            sheetY.intValue = y
            sheetX.intValue = left
            layout(w, h) { sheet.place(left, y) }
        }

        // 飞着的墨印：小组件右下角那枚，跟着纸框一起走到纸上墨印的位置、从 44dp 放大到 64dp。
        // 画在纸外面（不被纸框裁掉）；到了就交给纸上那枚 —— 一样大、一样位置，接缝看不出来
        if (flying) {
            val from = mic!!
            val to = sealBounds!!
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val r = lerp(from, to, Motion.Expand.transform(grow.value))
                        val side = r.width.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(side, side))
                        layout(0, 0) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
                    }
                    .background(colors.solid, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                MicIcon(
                    size = 26.dp,
                    tint = colors.onSolid,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.graphicsLayer {
                        // 小组件上的麦克风 20dp，纸上的 26dp
                        val s = 20f / 26f + (1f - 20f / 26f) * Motion.Expand.transform(grow.value)
                        scaleX = s
                        scaleY = s
                    }
                )
            }
        }
    }
}

/** 纸上摞的东西：状态 → 原话 → 回合 → 听写 / 用不了的原因 → 墨印 */
@Composable
private fun SheetContent(
    vm: QuickAddViewModel,
    onVoice: () -> Unit,
    onOpen: (String) -> Unit,
    onManual: () -> Unit,
    onOpenApp: () -> Unit,
    sealFaceVisible: Boolean,
    onSealPlaced: (Rect) -> Unit
) {
    val colors = DaodianColors.current
    StateLabel(vm)

    vm.asked?.let { AskedLine(it) }
    vm.turn?.let { turn ->
        Box(Modifier.padding(top = 14.dp)) {
            AssistantTurnRow(
                turn,
                object : TurnActions {
                    override fun toggleReasoning() = vm.toggleReasoning()
                    override fun openTrace(route: String) = onOpen(route)
                    override fun manualAdd() = onManual()
                    override fun retry() = vm.retry()
                }
            )
        }
    }

    Reveal(vm.blocked == null && !vm.aiBusy && !vm.saved) {
        Transcript(heard = vm.heard, listening = vm.listening, followUp = vm.turn != null, note = vm.note)
    }
    Reveal(vm.blocked != null) {
        Column(Modifier.padding(top = 12.dp)) {
            Text(vm.blocked.orEmpty(), style = DaodianType.prose, color = colors.ink2)
            Row(Modifier.padding(top = 14.dp)) { PillButton("去 app 里说", PillStyle.Solid, onOpenApp) }
        }
    }
    // 记好之后墨印退场，纸停一会儿自己缩回去
    Reveal(vm.blocked == null && !vm.saved) {
        SealControls(
            seal = when {
                vm.aiBusy -> Seal.Busy
                vm.listening -> Seal.Listening
                else -> Seal.Idle
            },
            level = vm.level,
            faceVisible = sealFaceVisible,
            onFacePlaced = onSealPlaced,
            onSeal = {
                when {
                    vm.aiBusy -> vm.stop()
                    vm.listening -> vm.finishListening()
                    else -> onVoice()
                }
            }
        )
    }
}

/** 纸顶上那一行小字：现在在干什么 */
@Composable
private fun StateLabel(vm: QuickAddViewModel) {
    val colors = DaodianColors.current
    val label = when {
        vm.blocked != null -> "用不了语音"
        vm.listening -> "在听"
        vm.aiBusy -> "在记"
        vm.saved -> "记好了"
        else -> "说一句"
    }
    Crossfade(label, animationSpec = Motion.flow(Motion.SHORT), label = "stateLabel") {
        Text(it, style = DaodianType.sectionLabel, color = colors.muted)
    }
}

/** 送去解析的那句话，左边一道细墨线 —— 它是引文，不是气泡 */
@Composable
private fun AskedLine(text: String) {
    val colors = DaodianColors.current
    Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(colors.rule2))
        Text(
            text,
            style = DaodianType.bodySmall,
            color = colors.ink2,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/**
 * 听写区：说起来就是边说边洇出来的宋体大字；没在说时是一句提示 ——
 * 印熄下来的原因（没听清、停了）直接顶替提示语，不另起一行红字：没听清不是出错。
 */
@Composable
private fun Transcript(heard: String, listening: Boolean, followUp: Boolean, note: String?) {
    val colors = DaodianColors.current
    val style = DaodianType.cardTitle.copy(fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 31.sp)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        when {
            heard.isNotEmpty() -> InkText(heard, streaming = listening, style = style, color = colors.ink, caret = listening)
            listening -> Text(if (followUp) "直接回答就行——" else "说吧——", style = style, color = colors.hint)
            else -> Text(
                note ?: if (followUp) "接着说，点一下印" else "点一下印，开始说",
                style = style,
                color = colors.hint
            )
        }
        if (heard.isEmpty() && !followUp) {
            Text(
                "比如「明天下午三点交房租」",
                style = DaodianType.caption,
                color = colors.hint,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private enum class Seal { Idle, Listening, Busy }

/** 墨印居中，底下一行小字说清按下去会怎样 */
@Composable
private fun SealControls(
    seal: Seal,
    level: Float,
    faceVisible: Boolean,
    onFacePlaced: (Rect) -> Unit,
    onSeal: () -> Unit
) {
    val colors = DaodianColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        MicSeal(seal, level, faceVisible, onFacePlaced, onSeal)
        Crossfade(
            when (seal) {
                Seal.Listening -> "停一下就收 · 点纸外取消"
                Seal.Busy -> "点一下，停"
                Seal.Idle -> "点一下，说"
            },
            animationSpec = Motion.flow(Motion.SHORT),
            label = "sealCaption"
        ) {
            Text(it, style = DaodianType.speakerTag, color = colors.hint)
        }
    }
}

/**
 * 墨印：实心墨色圆里一支麦克风（实心块一律墨色，§8.1 第 1 条）。
 * 听着的时候，底下一圈淡墨跟着音量胀缩，外面墨圈一圈圈洇开；
 * 送去解析之后褪成空心圈里一个墨块 —— 和对话页发送键的「停」同一个样子。
 */
@Composable
private fun MicSeal(
    seal: Seal,
    level: Float,
    faceVisible: Boolean,
    onFacePlaced: (Rect) -> Unit,
    onClick: () -> Unit
) {
    val colors = DaodianColors.current
    val listening = seal == Seal.Listening
    val busy = seal == Seal.Busy
    val swell by animateFloatAsState(if (listening) level else 0f, Motion.flow(Motion.CHAR), label = "sealSwell")
    val fill by animateColorAsState(
        if (busy) colors.solid.copy(alpha = 0f) else colors.solid, Motion.flow(Motion.SHORT), label = "sealFill"
    )
    val ring by animateColorAsState(if (busy) colors.rule2 else colors.solid, Motion.flow(Motion.SHORT), label = "sealRing")

    Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
        // 无限循环只在真的在听时才进组合 —— 挂着不用也会一直要帧
        // 飞着的那枚还没落到这儿时，墨圈先别洇
        if (listening && faceVisible) InkRipples(colors.ink2)
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    val s = 1f + swell * 0.6f
                    scaleX = s
                    scaleY = s
                }
                .background(colors.ink.copy(alpha = 0.08f), CircleShape)
        )
        Box(
            Modifier
                .size(64.dp)
                .onGloballyPositioned { onFacePlaced(it.boundsInWindow()) }
                .graphicsLayer { alpha = if (faceVisible) 1f else 0f }
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, ring, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(busy, animationSpec = Motion.flow(Motion.SHORT), label = "sealGlyph") { stop ->
                if (stop) StopIcon(size = 24.dp, tint = colors.ink2)
                else MicIcon(size = 26.dp, tint = colors.onSolid, strokeWidth = 1.5.dp)
            }
        }
    }
}

/** 墨滴落在纸上：两圈墨线错开半拍往外洇，越走越淡。一趟的时长借用墨条洇染的 [Motion.WASH] */
@Composable
private fun InkRipples(color: Color) {
    val phase by rememberInfiniteTransition(label = "ripples").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.WASH, easing = LinearEasing)),
        label = "ripplePhase"
    )
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val base = 32.dp.toPx()
                val reach = size.minDimension / 2 - base
                val stroke = Stroke(1.dp.toPx())
                repeat(2) { i ->
                    val p = (phase + i * 0.5f) % 1f
                    drawCircle(
                        color.copy(alpha = 0.35f * (1f - p)),
                        radius = base + Motion.Settle.transform(p) * reach,
                        style = stroke
                    )
                }
            }
    )
}

/**
 * 纸的外形：[progress] 从 0 到 1，从小组件那一块（[from]，已换算成纸自己的坐标，圆角 [fromCorner]）
 * 长成整张纸（圆角 [corner]）。桌面没给位置时，从纸底边正中一个印大小的圆长出来。
 */
private class RevealShape(
    private val progress: Float,
    private val from: Rect?,
    private val fromCorner: Float,
    private val corner: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val full = Rect(Offset.Zero, size)
        val start = from ?: with(density) {
            val r = 32.dp.toPx()
            Rect(center = Offset(size.width / 2, size.height - r), radius = r)
        }
        val p = progress.coerceIn(0f, 1f)
        // 退回墨印那一小块时，系统圆角比它的一半还大 —— 夹一下，就是个圆
        val startCorner = if (from != null) minOf(fromCorner, minOf(start.width, start.height) / 2)
        else minOf(start.width, start.height) / 2
        return Outline.Rounded(
            RoundRect(lerp(start, full, p), CornerRadius(startCorner + (corner - startCorner) * p))
        )
    }
}

/** 纸上的一块：展开着进场、收起着退场，和对话页回合里的块一个规矩 */
@Composable
private fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(Motion.settle(), expandFrom = Alignment.Top) + fadeIn(Motion.flow()),
        exit = shrinkVertically(Motion.flow(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
    ) { content() }
}
