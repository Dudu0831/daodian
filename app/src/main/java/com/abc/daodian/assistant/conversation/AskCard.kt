package com.abc.daodian.assistant.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.assistant.plugin.AssistantPlugin
import com.abc.daodian.assistant.plugin.AssistantPlugins
import com.abc.daodian.runtime.ask.AskQuestion
import com.abc.daodian.runtime.ask.AskRequest
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.runtime.ask.Pick
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.InkChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 问卡：对话里唯一的卡片。模型拿不准时先猜好几个答案，你点一下；都不对就在输入条里自己写。见 DESIGN.md §6.6。
 *
 * 等你答时和输入条合成一张纸（11-12「甲 · 连体」）：上半截是问，下半截还是输入条，外壳归 ConversationScreen 画，
 * 这里不画底、不画边。多题的卡一次看一题，顶上一排细线（答了的朱砂、当前墨色），点完自动翻到下一题没答的。
 *
 * 猜测固定一行，装不下横着滑（[ChipRows]）。多题的几题是一摞横着翻的页，在题上左右滑就翻；高取最高的那页，翻的时候卡不动。
 *
 * 都不是：在下半截的输入条里写，或者按住说，答的是眼前这一题，发出去落成一颗实心胶囊，点它取下来接着改。
 * 没有「其他…」那颗、也没有「直接说」了（11-12：两个能打字的地方、一个答一题一个作废整张卡，分不清）。
 *
 * 答完后卡片收起，「答」印盖在回合抬头「· 到点」右侧的伴随印上。
 */

/** 问卡的一题答成了什么，收起后细目里写它；null = 先放着 */
fun answerLabelOf(q: AskQuestion, pick: Pick?, single: Boolean): String? = when (pick) {
    null -> null
    is Pick.Typed -> pick.text
    is Pick.Option -> q.options.getOrNull(pick.index)?.let { o ->
        if (single && o.detail != null) "${o.label} · ${o.detail}" else o.label
    }
}

/** 点完了、直接说了、没答：卡片从正文收起，化进抬头伴随印 */
internal val AskState.sealed: Boolean
    get() = this == AskState.ANSWERED || this == AskState.SAID || this == AskState.UNANSWERED

@Composable
fun AskCard(
    block: TurnBlock.Ask,
    onPick: (question: Int, option: Int) -> Unit,
    /** 点了自己写的那颗：答案取下，字交回输入条接着改 */
    onRetype: (question: Int, text: String) -> Unit,
    /** 多题的卡翻到了第几题 —— 输入条里写的答的是这一题 */
    onPage: (question: Int) -> Unit,
    onSubmit: () -> Unit
) {
    val request = remember(block.request, block.arguments) { block.request ?: AskUserTool.draftOf(block.arguments) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 13.dp)
            .animateContentSize(Motion.flow())
    ) {
        key(block.callId) { Asking(block, request, onPick, onRetype, onPage, onSubmit) }
    }
}

/** 等你答（或者参数还在流）的那张卡 */
@Composable
private fun Asking(
    block: TurnBlock.Ask,
    request: AskRequest,
    onPick: (question: Int, option: Int) -> Unit,
    onRetype: (question: Int, text: String) -> Unit,
    onPage: (question: Int) -> Unit,
    onSubmit: () -> Unit
) {
    val colors = DaodianColors.current
    val live = block.state == AskState.READY
    // 多题的卡是一摞横着翻的页：手指在题上左右滑，或者点卡头的细线
    val pager = rememberPagerState { request.questions.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()
    val current = pager.currentPage.coerceIn(0, request.questions.lastIndex.coerceAtLeast(0))
    // 这一题刚点完：停一下让你看清点的是哪个，翻到下一题没答的。改答案、取消不翻
    val answered = block.picks.count { it != null }
    var lastAnswered by remember { mutableIntStateOf(answered) }
    LaunchedEffect(answered) {
        val advance = answered > lastAnswered
        lastAnswered = answered
        if (!advance) return@LaunchedEffect
        delay(PAGE_HOLD_MS)
        val total = request.questions.size
        (1 until total).map { (current + it) % total }
            .firstOrNull { block.picks.getOrNull(it) == null }
            ?.let { pager.animateScrollToPage(it, animationSpec = Motion.flow(PAGE_TURN_MS)) }
    }
    LaunchedEffect(current) { onPage(current) }
    val side = Modifier.padding(horizontal = SIDE)

    // 参数刚开始流，一题都还没收全：只有「问」和标签
    if (request.questions.isEmpty()) {
        Row(
            side.heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            AskGlyph("问", colors.accent)
            Text(request.label.ifBlank { "问一下" }, style = DaodianType.caption, color = colors.ink2)
        }
        return
    }

    // 一题和多题是同一副骨架（都是翻页器，一题就是只有一页），差别只在两样「收全了才知道」的东西：
    // 多题的卡头（细线 + 交卷）、一题的「问」印。它们等参数收全（[live]）再浮出来 ——
    // 流到第一题时还不知道后面有没有，以前先画成一题的样子、第二题到了整张换成多题，闪一下。
    // 卡是钉在输入条上面的，卡头从顶上长出来时底下的题在屏幕上不动
    val single = request.questions.size == 1
    AnimatedVisibility(
        visible = live && !single,
        enter = expandVertically(Motion.settle()) + fadeIn(Motion.flow()),
        exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.exit())
    ) {
        AskProgress(
            picks = List(request.questions.size) { block.picks.getOrNull(it) },
            current = current,
            modifier = side,
            onSelect = { i -> scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.flow(PAGE_TURN_MS)) } },
            onSubmit = onSubmit
        )
    }
    Box {
        // 几页一直都在（beyondViewportPageCount）：翻页器的高取最高的那页，翻的时候卡不动；
        // 「＋ 打标签」那行也早就读好了，不会翻过去才晚一帧冒出来
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = SIDE),
            pageSpacing = SIDE * 2,
            beyondViewportPageCount = request.questions.size,
            verticalAlignment = Alignment.Top,
            // 还在流的时候不给翻：后面的题可能还没收全
            userScrollEnabled = live && !single,
            key = { it }
        ) { i ->
            Question(
                q = request.questions[i],
                pick = block.picks.getOrNull(i),
                live = live,
                // 一题：右上角要盖「问」印，字给它让出一截
                glyphRoom = single,
                onPick = { onPick(i, it) },
                onRetype = { onRetype(i, it) }
            )
        }
        val stamp by animateFloatAsState(if (live && single) 1f else 0f, Motion.flow(), label = "askStamp")
        AskGlyph(
            "问", colors.accent,
            Modifier.align(Alignment.TopEnd).padding(end = SIDE, top = 3.dp).graphicsLayer { alpha = stamp }
        )
    }
}

/** 问卡里的字离纸边多远 */
private val SIDE = 16.dp

/** 点细线、答完自动翻到下一题，那一页走多久 */
private const val PAGE_TURN_MS = 320

/** 点完一题到跳下一题之间停多久：看得清点的是哪个，又不至于干等 */
private const val PAGE_HOLD_MS = 260L

/**
 * 多题卡的卡头：一题一段细线，答了的朱砂、当前墨色、没到的浅灰，点哪段去哪题；最右是交卷的那颗（[SubmitPill]）。
 * 这一题是多少钱写在题里，卡头不写字
 */
@Composable
private fun AskProgress(picks: List<Pick?>, current: Int, modifier: Modifier, onSelect: (Int) -> Unit, onSubmit: () -> Unit) {
    val colors = DaodianColors.current
    Row(modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            picks.forEachIndexed { i, pick ->
                val tint by animateColorAsState(
                    when {
                        i == current -> colors.ink
                        pick != null -> colors.accent
                        else -> colors.rule2
                    },
                    Motion.flow(Motion.SHORT), label = "askSeg"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) }
                        .semantics { contentDescription = "第 ${i + 1} 题" + if (pick != null) "，答了" else "" },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(tint, RoundedCornerShape(1.5.dp)))
                }
            }
        }
        SubmitPill(
            answered = picks.count { it != null },
            total = picks.size,
            modifier = Modifier.padding(start = 12.dp),
            onClick = onSubmit
        )
    }
}

/**
 * 多题卡右上角那颗，一个时刻只说一件事（11-12，以前是「都先放着 / 就这样」，一个词又是交卷又是放弃）：
 * - 一题没答：描边、灰字「先放着」—— 点了整张卡收起，记成先放着
 * - 答了几题：描边、墨字「提交 2/4」—— 交已经答的，剩下的先放着
 * - 答齐了：实心墨底「提交」
 * 参数还在流时整个卡头都还没出来
 */
@Composable
private fun SubmitPill(answered: Int, total: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val all = total > 0 && answered == total
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.flow(Motion.SHORT), label = "pillPress")
    val fill by animateColorAsState(if (all) colors.solid else colors.solid.copy(alpha = 0f), Motion.flow(), label = "pillFill")
    val edge by animateColorAsState(if (all) colors.solid else colors.rule2, Motion.flow(), label = "pillEdge")
    val ink by animateColorAsState(
        when {
            all -> colors.onSolid
            answered == 0 -> colors.muted
            else -> colors.ink
        },
        Motion.flow(), label = "pillInk"
    )
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(30.dp)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .animateContentSize(Motion.flow(Motion.SHORT)),
        contentAlignment = Alignment.Center
    ) {
        val text = when {
            all -> "提交"
            answered == 0 -> "先放着"
            else -> "提交 $answered/$total"
        }
        Text(text, style = DaodianType.bodySmall, color = ink, maxLines = 1)
    }
}

/** 问句的字：宋体 17 */
private val HeadPrompt = DaodianType.cardTitle.copy(fontSize = 17.sp, lineHeight = 25.sp)

private val HintText = DaodianType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp)

private val AmountText = DaodianType.rowTitle.copy(fontSize = 22.sp, lineHeight = 28.sp)

/**
 * 卡里的一题：问账的金额打头（宋体 22）、后面是出处；别的是出处 + 问句。底下依据、猜测、模块挂的那一块。
 * [glyphRoom]：右上角盖着「问」印，字给它让出一截
 */
@Composable
private fun Question(
    q: AskQuestion,
    pick: Pick?,
    live: Boolean,
    glyphRoom: Boolean = false,
    onPick: (Int) -> Unit,
    onRetype: (String) -> Unit
) {
    val colors = DaodianColors.current
    // 问卡的类在 :runtime 里，跨模块的属性不能 smart cast，先取出来
    val amount = q.amount
    val origin = q.context
    val prompt = q.prompt
    val hint = q.hint
    val ref = q.ref
    Column {
        Column(Modifier.padding(end = if (glyphRoom) 28.dp else 0.dp)) {
            if (amount != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(amount, style = AmountText, color = colors.ink, modifier = Modifier.alignByBaseline())
                    Text(
                        origin.orEmpty(), style = DaodianType.caption, color = colors.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alignByBaseline()
                    )
                }
                if (prompt != null) Text(prompt, style = DaodianType.bodySmall.copy(fontSize = 14.sp), color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
            } else {
                if (origin != null) Text(origin, style = DaodianType.caption, color = colors.muted)
                if (prompt != null) Text(prompt, style = HeadPrompt, color = colors.ink)
            }
            if (hint != null) Text(hint, style = HintText, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
        }
        Guesses(q, pick, live, onPick, onRetype)
        if (live && ref != null) Addon(ref)
    }
}

/**
 * 一题的猜测。都不是的话在底下输入条里写（或按住说），发出去落成排在最前的一颗实心胶囊（11-12，
 * 以前这里有一颗虚线「其他…」，点了原地长出输入框）；点那颗 = 取下来，字交回输入条接着改
 */
@Composable
private fun Guesses(q: AskQuestion, pick: Pick?, live: Boolean, onPick: (Int) -> Unit, onRetype: (String) -> Unit) {
    // 参数还在流：猜测先半透明，点不了 —— 可能还在变
    val ready by animateFloatAsState(if (live) 1f else 0.55f, Motion.flow(), label = "askReady")
    val selected = (pick as? Pick.Option)?.index
    val typed = (pick as? Pick.Typed)?.text
    if (q.options.isEmpty() && typed == null) return
    Box(Modifier.padding(top = 11.dp).graphicsLayer { alpha = ready }) {
        ChipRows {
            // 自己写的排最前：猜测多到要横滑时，排在最后就在屏幕外了
            if (typed != null) ChipLabel(typed, selected = true, dimmed = false, enabled = live) { onRetype(typed) }
            q.options.forEachIndexed { j, o ->
                ChipLabel(
                    o.label, detail = o.detail,
                    selected = selected == j, dimmed = pick != null && selected != j, enabled = live
                ) { onPick(j) }
            }
        }
    }
}

private val CHIP_HEIGHT = 34.dp
private val CHIP_GAP = 7.dp

/** 猜测排几行：固定一行，装不下横着滑（11-12 先做过「最多两行」，这题两行下一题一行，换题时卡一高一矮） */
private const val CHIP_ROWS = 1

/**
 * 一排猜测：[CHIP_ROWS] 行装得下就这么排；装不下（猜测多、字长）就匀成 [CHIP_ROWS] 行横着滑，
 * 还有没露出来的那一侧边上淡出去。一颗最宽占八成半，再长截断 —— 不然一颗就占满一屏，后面的看不出能滑
 */
@Composable
private fun ChipRows(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewport = constraints.maxWidth
        val gap = with(LocalDensity.current) { CHIP_GAP.roundToPx() }
        val scroll = rememberScrollState()
        // 装得下就不接手势：这一排上的左右滑留给翻页
        Layout(content, Modifier.edgeFade(scroll).horizontalScroll(scroll, enabled = scroll.maxValue > 0)) { measurables, _ ->
            val chips = measurables.map { it.measure(Constraints(maxWidth = (viewport * 0.85f).toInt())) }
            fun widthOf(row: List<Placeable>) = row.sumOf { it.width } + gap * (row.size - 1).coerceAtLeast(0)
            val wrapped = mutableListOf(mutableListOf<Placeable>())
            chips.forEach { c ->
                val row = wrapped.last()
                if (row.isNotEmpty() && widthOf(row) + gap + c.width > viewport) wrapped += mutableListOf(c) else row += c
            }
            val rows: List<List<Placeable>> = (if (wrapped.size <= CHIP_ROWS) wrapped else {
                // 按宽度匀开：每行攒到平均宽度就换行，最后一行兜底
                val target = widthOf(chips) / CHIP_ROWS
                val even = mutableListOf(mutableListOf<Placeable>())
                chips.forEach { c ->
                    if (even.size < CHIP_ROWS && even.last().isNotEmpty() && widthOf(even.last()) >= target) even += mutableListOf(c)
                    else even.last() += c
                }
                even
            }).filter { it.isNotEmpty() }
            val rowHeight = chips.maxOfOrNull { it.height } ?: 0
            val width = maxOf(viewport, rows.maxOfOrNull(::widthOf) ?: 0)
            val height = rows.size * rowHeight + gap * (rows.size - 1).coerceAtLeast(0)
            layout(width, height) {
                rows.forEachIndexed { r, row ->
                    var x = 0
                    row.forEach {
                        it.placeRelative(x, r * (rowHeight + gap))
                        x += it.width + gap
                    }
                }
            }
        }
    }
}

/** 横滑的那一排：还能往哪边滑，哪边的边上就淡出去 */
private fun Modifier.edgeFade(scroll: ScrollState, width: Dp = 22.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (scroll.canScrollForward) drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
            topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn
        )
        if (scroll.canScrollBackward) drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
            size = Size(w, size.height), blendMode = BlendMode.DstIn
        )
    }

/** 模块在这题底下挂的一块（记账：顺手打标签），见 [AssistantPlugin.AskAddon]。不是它的 ref 就什么都不画 */
@Composable
private fun Addon(ref: String) {
    val plugins = remember { AssistantPlugins.plugins }
    plugins.forEach { it.AskAddon(ref) }
}

@Composable
private fun ChipLabel(
    text: String,
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    detail: String? = null,
    onClick: () -> Unit
) {
    InkChip(
        selected = selected, dimmed = dimmed, enabled = enabled,
        shape = RoundedCornerShape(CHIP_HEIGHT / 2),
        modifier = Modifier.height(CHIP_HEIGHT),
        onClick = onClick
    ) { onInk ->
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text,
                style = DaodianType.bodySmall.copy(fontSize = 14.sp),
                color = onInk.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (detail != null) Text(detail, style = DaodianType.caption, color = onInk.sub, maxLines = 1)
        }
    }
}

/** 印上的一个字：描边方框，不填色。卡头的「问」18dp；伴随印里的 13dp（[small]） */
@Composable
internal fun AskGlyph(text: String, tint: Color, modifier: Modifier = Modifier, small: Boolean = false) {
    val size = if (small) 13.dp else 18.dp
    val font: TextUnit = if (small) 9.sp else 11.sp
    val corner = if (small) 2.5.dp else 3.dp
    Box(modifier.size(size).border(1.dp, tint, RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        Text(text, style = DaodianType.seal.copy(fontSize = font, lineHeight = font), color = tint)
    }
}

/**
 * 答完那一刻在抬头伴随印上盖下来的「答」：从 1.45 倍、−10° 带回弹落下，印边洇开一圈。
 */
@Composable
internal fun StampGlyph(text: String, fresh: Boolean) {
    val colors = DaodianColors.current
    val scale = remember { Animatable(if (fresh) 1.45f else 1f) }
    val rotation = remember { Animatable(if (fresh) -10f else 0f) }
    val alpha = remember { Animatable(if (fresh) 0f else 1f) }
    val bleed = remember { Animatable(1f) }
    LaunchedEffect(fresh) {
        if (!fresh) return@LaunchedEffect
        scale.snapTo(1.45f)
        rotation.snapTo(-10f)
        alpha.snapTo(0f)
        bleed.snapTo(0f)
        launch { scale.animateTo(1f, Motion.stamp()) }
        launch { rotation.animateTo(0f, Motion.stamp()) }
        launch { alpha.animateTo(1f, tween(Motion.SHORT)) }
        launch {
            delay(180)
            bleed.animateTo(1f, tween(640, easing = LinearOutSlowInEasing))
        }
    }
    Box(
        Modifier
            .size(13.dp)
            .drawBehind {
                val b = bleed.value
                if (b < 1f) {
                    val grow = 5.dp.toPx() * b
                    drawRoundRect(
                        colors.accent.copy(alpha = 0.42f * (1f - b)),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(2.5.dp.toPx() + grow),
                        style = Stroke(1.dp.toPx())
                    )
                }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotation.value
                this.alpha = alpha.value
            }
    ) {
        AskGlyph(text, colors.accent, small = true)
    }
}
