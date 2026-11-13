package com.abc.daodian.assistant.conversation.ask

import com.abc.daodian.assistant.conversation.TurnBlock
import com.abc.daodian.assistant.conversation.AskState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abc.daodian.runtime.ask.AskQuestion
import com.abc.daodian.runtime.ask.AskRequest
import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.runtime.ask.Pick
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
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
    // 多题的卡头（细线 + 交卷）、一题的「问」印。
    // 流到第一题时还不知道后面有没有，以前先画成一题的样子、第二题到了整张换成多题，闪一下。
    // 第二题收全时卡头的细线从顶上长出来，之后每收全一题多长一段，卡一直停在第一题；交卷那颗等参数收全才淡进来（11-13）。
    // 卡是钉在输入条上面的，卡头从顶上长出来时底下的题在屏幕上不动
    val single = request.questions.size == 1
    AnimatedVisibility(
        visible = !single,
        enter = expandVertically(Motion.settle()) + fadeIn(Motion.flow()),
        exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.exit())
    ) {
        AskProgress(
            picks = List(request.questions.size) { block.picks.getOrNull(it) },
            current = current,
            live = live,
            modifier = side,
            onSelect = { i -> if (live) scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.flow(PAGE_TURN_MS)) } },
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
private fun AskProgress(
    picks: List<Pick?>,
    current: Int,
    live: Boolean,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
    onSubmit: () -> Unit
) {
    val colors = DaodianColors.current
    // 卡头出来时已有的几段直接画满；之后流到的题，那一段从零宽长出来
    val initial = remember { picks.size }
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
                var grown by remember { mutableStateOf(i < initial) }
                LaunchedEffect(Unit) { grown = true }
                val weight by animateFloatAsState(if (grown) 1f else 0.001f, Motion.flow(), label = "askSegGrow")
                Box(
                    Modifier
                        .weight(weight)
                        .graphicsLayer { alpha = weight }
                        .height(30.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) }
                        .semantics { contentDescription = "第 ${i + 1} 题" + if (pick != null) "，答了" else "" },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(tint, RoundedCornerShape(1.5.dp)))
                }
            }
        }
        AnimatedVisibility(
            visible = live,
            enter = expandHorizontally(Motion.flow()) + fadeIn(Motion.flow()),
            exit = shrinkHorizontally(Motion.flow()) + fadeOut(Motion.exit())
        ) {
            SubmitPill(
                answered = picks.count { it != null },
                total = picks.size,
                modifier = Modifier.padding(start = 12.dp),
                onClick = onSubmit
            )
        }
    }
}

/**
 * 多题卡右上角那颗，一个时刻只说一件事（11-12，以前是「都先放着 / 就这样」，一个词又是交卷又是放弃）：
 * - 一题没答：描边、灰字「先放着」—— 点了整张卡收起，记成先放着
 * - 答了几题：描边、墨字「提交 2/4」—— 交已经答的，剩下的先放着
 * - 答齐了：实心墨底「提交」
 * 参数还在流时它不出来，收全了才淡进来
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
