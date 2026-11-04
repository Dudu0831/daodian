package com.abc.daodian.assistant.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.InkChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * 问卡：对话里唯一的卡片。模型拿不准时先猜好几个答案，你点一下；都不对就点「其他…」自己写，
 * 或者什么都不点、直接在输入框里说一句。见 DESIGN.md §6.6。
 *
 * 等你答时钉在输入条上面，不进对话列表。多题的卡一次看一题，顶上一排页签（金额），点完自动跳到下一题没答的。
 *
 * 「其他…」原地变成一行输入框（[OtherField]），键盘跟着弹，底下的输入条让开；写完点对勾落成一颗实心胶囊，点它能改。
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

/** 一题、问的是一句话（不是一笔账）：问句直接写在卡头「问」字旁边 */
private fun AskRequest.headed(): AskQuestion? =
    questions.singleOrNull()?.takeIf { single && it.amount == null && it.prompt != null }

@Composable
fun AskCard(
    block: TurnBlock.Ask,
    onPick: (question: Int, option: Int) -> Unit,
    onOther: (question: Int) -> Unit,
    onTyped: (question: Int, text: String) -> Unit,
    onSubmit: () -> Unit
) {
    val colors = DaodianColors.current
    val request = remember(block.request, block.arguments) { block.request ?: AskUserTool.draftOf(block.arguments) }
    val headed = request.headed() != null
    val shape = RoundedCornerShape(6.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = if (headed) 10.dp else 8.dp,
                bottom = if (headed) 12.dp else 10.dp
            )
            .animateContentSize(Motion.flow())
    ) {
        Asking(block, request, onPick, onOther, onTyped, onSubmit)
    }
}

/** 等你答（或者参数还在流）的那张卡 */
@Composable
private fun Asking(
    block: TurnBlock.Ask,
    request: AskRequest,
    onPick: (question: Int, option: Int) -> Unit,
    onOther: (question: Int) -> Unit,
    onTyped: (question: Int, text: String) -> Unit,
    onSubmit: () -> Unit
) {
    val colors = DaodianColors.current
    val live = block.state == AskState.READY
    val headed = request.headed()
    // 这张卡一出来就已经有的题（一次性请求）一起错峰升起；流式时每收全一题升一题
    val paged = headed == null && request.questions.size > 1
    var at by rememberSaveable(block.callId) { mutableIntStateOf(0) }
    val current = at.coerceAtMost(request.questions.lastIndex).coerceAtLeast(0)
    // 这一题刚点完：停一下让你看清点的是哪个，跳到下一题没答的。改答案、取消不跳
    val answered = block.picks.count { it != null }
    var lastAnswered by remember { mutableIntStateOf(answered) }
    LaunchedEffect(answered) {
        val advance = answered > lastAnswered
        lastAnswered = answered
        if (!advance || block.editing != null) return@LaunchedEffect
        delay(PAGE_HOLD_MS)
        val total = request.questions.size
        (1 until total).map { (current + it) % total }
            .firstOrNull { block.picks.getOrNull(it) == null }
            ?.let { at = it }
    }
    // 页签切走时这一题不在界面上了，「其他…」的草稿要留着
    val drafts = rememberSaveableStateHolder()

    Column {
        if (headed != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AskGlyph("问", colors.accent, Modifier.padding(top = 3.dp))
                Text(headed.prompt.orEmpty(), style = HeadPrompt, color = colors.ink, modifier = Modifier.weight(1f))
            }
            HeadedQuestion(
                headed, block.picks.firstOrNull(), editing = block.editing == 0, live = live,
                draftKey = block.callId,
                onPick = { onPick(0, it) }, onOther = { onOther(0) }, onTyped = { onTyped(0, it) }
            )
        } else if (paged) {
            AskTabs(request, block.picks, current) { i ->
                block.editing?.takeIf { it != i }?.let(onOther)
                at = i
            }
            val i = current
            drafts.SaveableStateProvider("${block.callId}#$i") {
                Question(
                    q = request.questions[i],
                    pick = block.picks.getOrNull(i),
                    editing = block.editing == i,
                    live = live,
                    draftKey = "${block.callId}#$i",
                    paged = true,
                    onPick = { onPick(i, it) },
                    onOther = { onOther(i) },
                    onTyped = { onTyped(i, it) }
                )
            }
        } else {
            Row(
                Modifier.heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                AskGlyph("问", colors.accent)
                Text(
                    request.label.ifBlank { "问一下" },
                    style = DaodianType.caption,
                    color = colors.ink2
                )
            }
            request.questions.firstOrNull()?.let { q ->
                Question(
                    q = q,
                    pick = block.picks.firstOrNull(),
                    editing = block.editing == 0,
                    live = live,
                    draftKey = "${block.callId}#0",
                    onPick = { onPick(0, it) },
                    onOther = { onOther(0) },
                    onTyped = { onTyped(0, it) }
                )
            }
        }

        // 卡脚只有多题的卡才有：一题的点了就算答。写「其他…」时收起，键盘上面只留这一题
        AnimatedVisibility(
            visible = live && !request.single && block.editing == null,
            enter = expandVertically(Motion.settle()) + fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.exit())
        ) {
            val n = block.picks.count { it != null }
            val total = request.questions.size
            val unit = if (request.questions.all { it.amount != null }) "笔" else "题"
            Column {
                HorizontalDivider(color = colors.ruleSoft)
                Row(
                    Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SolidPill(if (n == 0) "都先放着" else "就这样", onSubmit)
                    Crossfade(
                        when (n) {
                            0 -> "点猜测，或者直接说"
                            total -> "$n $unit 都点了"
                            else -> "点了 $n $unit · 没点的先放着"
                        },
                        animationSpec = tween(120),
                        label = "askCount"
                    ) { Text(it, style = DaodianType.caption, color = colors.muted) }
                }
            }
        }
    }
}

/** 点完一题到跳下一题之间停多久：看得清点的是哪个，又不至于干等 */
private const val PAGE_HOLD_MS = 260L

/** 多题卡的卡头：「问」字 + 一排页签。问账的写金额，别的写第几题；答过的带小勾 */
@Composable
private fun AskTabs(request: AskRequest, picks: List<Pick?>, current: Int, onSelect: (Int) -> Unit) {
    val colors = DaodianColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskGlyph("问", colors.accent, Modifier.padding(end = 6.dp))
            request.questions.forEachIndexed { i, q ->
                val on = i == current
                Column(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clickable(role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            q.amount?.removeSuffix(".00") ?: "第 ${i + 1} 题",
                            style = TabText,
                            color = if (on) colors.ink else colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (picks.getOrNull(i) != null) CheckIcon(size = 10.dp, tint = colors.accent)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) colors.accent else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = colors.ruleSoft)
    }
}

private val TabText = DaodianType.rowTitle.copy(fontSize = 14.sp)

/** 卡头问句的字：宋体 16，和多题卡里没有金额的那种问句一样大 */
private val HeadPrompt = DaodianType.cardTitle.copy(fontSize = 16.sp, lineHeight = 24.sp)

/** 一题、问句写在卡头的：底下出处、依据，一排胶囊 + 虚线「其他…」（点了就算答，写完点对勾也算答） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadedQuestion(
    q: AskQuestion,
    pick: Pick?,
    editing: Boolean,
    live: Boolean,
    draftKey: String,
    onPick: (Int) -> Unit,
    onOther: () -> Unit,
    onTyped: (String) -> Unit
) {
    val colors = DaodianColors.current
    val ready by animateFloatAsState(if (live) 1f else 0.55f, Motion.flow(), label = "askReady")
    val selected = (pick as? Pick.Option)?.index
    // 问卡的类在 :runtime 里，跨模块的属性不能 smart cast，先取出来
    val origin = q.context
    val hint = q.hint
    val ref = q.ref
    // 和问句左边对齐：「问」字 18 + 间距 9
    Column(Modifier.padding(start = 27.dp)) {
        if (origin != null) Text(origin, style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(top = 1.dp))
        if (hint != null) Text(hint, style = HintText, color = colors.muted, modifier = Modifier.padding(top = 1.dp))
    }
    FlowRow(
        Modifier.padding(top = 10.dp).graphicsLayer { alpha = ready },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        q.options.forEachIndexed { j, o ->
            ChipLabel(
                o.label, detail = o.detail, height = 34.dp,
                selected = selected == j, dimmed = (pick != null || editing) && selected != j, enabled = live
            ) { onPick(j) }
        }
        OtherSlot(pick, editing, live, draftKey, height = 34.dp, onOther = onOther, onTyped = onTyped)
    }
    if (live && ref != null) Addon(ref)
}

private val HintText = DaodianType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp)

/** 卡里的一题：金额打头、后面是出处（没有金额的是出处 + 问句），依据，一排猜测 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Question(
    q: AskQuestion,
    pick: Pick?,
    editing: Boolean,
    live: Boolean,
    draftKey: String,
    paged: Boolean = false,
    onPick: (Int) -> Unit,
    onOther: () -> Unit,
    onTyped: (String) -> Unit
) {
    val colors = DaodianColors.current
    // 参数还在流：猜测先半透明，点不了 —— 可能还在变
    val ready by animateFloatAsState(if (live) 1f else 0.55f, Motion.flow(), label = "askReady")
    Column {
        if (!paged) HorizontalDivider(color = colors.ruleSoft)
        Column(Modifier.padding(top = 9.dp, bottom = 10.dp)) {
            // 问卡的类在 :runtime 里，跨模块的属性不能 smart cast，先取出来
            val amount = q.amount
            val origin = q.context
            val prompt = q.prompt
            val hint = q.hint
            val ref = q.ref
            if (amount != null && !paged) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(amount, style = AmountText, color = colors.ink, modifier = Modifier.alignByBaseline())
                    Text(
                        origin.orEmpty(), style = DaodianType.caption, color = colors.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alignByBaseline()
                    )
                }
            } else {
                if (origin != null) Text(origin, style = DaodianType.caption, color = colors.muted)
                if (prompt != null) Text(prompt, style = HeadPrompt, color = colors.ink)
            }
            if (hint != null) Text(hint, style = HintText, color = colors.muted, modifier = Modifier.padding(top = 2.dp))

            val selected = (pick as? Pick.Option)?.index
            FlowRow(
                Modifier.padding(top = 7.dp).graphicsLayer { alpha = ready },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                q.options.forEachIndexed { j, o ->
                    ChipLabel(o.label, selected = selected == j, dimmed = (pick != null || editing) && selected != j, enabled = live) { onPick(j) }
                }
                OtherSlot(pick, editing, live, draftKey, onOther = onOther, onTyped = onTyped)
            }
            if (live && ref != null) Addon(ref)
        }
    }
}

private val AmountText = DaodianType.rowTitle.copy(fontSize = 15.sp)

/**
 * 一题末尾的「其他…」那一格：没写过是虚线「其他…」；写着是一整行输入框；写好了是一颗实心胶囊，点它接着改。
 * 草稿按题记着（[draftKey]），收起再点开还在
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.OtherSlot(
    pick: Pick?,
    editing: Boolean,
    live: Boolean,
    draftKey: String,
    height: Dp = 32.dp,
    onOther: () -> Unit,
    onTyped: (String) -> Unit
) {
    val typed = (pick as? Pick.Typed)?.text
    var draft by rememberSaveable(draftKey) { mutableStateOf("") }
    // 点开写好的那颗接着改：从它的字起笔
    LaunchedEffect(editing) { if (editing && draft.isEmpty()) draft = typed.orEmpty() }
    when {
        editing -> OtherField(
            text = draft,
            onText = { draft = it },
            height = height,
            onDone = {
                val t = draft.trim()
                // 什么都没写就点对勾：收起，当没点过「其他…」
                if (t.isEmpty()) onOther() else onTyped(t)
            },
            onClose = onOther,
            modifier = Modifier.fillMaxWidth()
        )
        typed != null -> ChipLabel(typed, selected = true, dimmed = false, enabled = live, height = height, onClick = onOther)
        else -> ChipLabel("其他…", selected = false, dimmed = false, enabled = live, dashed = true, height = height, onClick = onOther)
    }
}

/**
 * 「其他…」展开成的那行：朱砂边、纸色底，一出来就拿焦点、弹键盘；回车或右边的对勾都是写好了。
 * 系统返回收了键盘 = 不写了（[onClose]），草稿留着 —— 不然输入条让开了就回不来
 */
@Composable
private fun OtherField(text: String, onText: (String) -> Unit, height: Dp, onDone: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(Unit) {
        snapshotFlow { ime.getBottom(density) > 0 }.dropWhile { !it }.first { !it }
        close()
    }
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .height(height + 2.dp)
            .background(colors.paper, shape)
            .border(1.dp, colors.accent, shape)
            .padding(start = 13.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BasicTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            textStyle = DaodianType.bodySmall.copy(fontSize = 14.sp, color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f).focusRequester(focus)
        )
        Box(
            Modifier
                .size(height - 6.dp)
                .background(colors.solid, CircleShape)
                .clickable(role = Role.Button, onClick = onDone)
                .semantics { contentDescription = "就是这个" },
            contentAlignment = Alignment.Center
        ) { CheckIcon(size = 13.dp, tint = colors.onSolid) }
    }
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
    dashed: Boolean = false,
    accent: Boolean = false,
    detail: String? = null,
    height: Dp = 32.dp,
    onClick: () -> Unit
) {
    val roomy = height > 32.dp
    InkChip(
        selected = selected, dimmed = dimmed, enabled = enabled, dashed = dashed, accent = accent,
        shape = RoundedCornerShape(height / 2),
        modifier = Modifier.height(height),
        onClick = onClick
    ) { onInk ->
        Row(
            Modifier.padding(horizontal = if (roomy) 14.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text,
                style = DaodianType.bodySmall.copy(fontSize = if (roomy) 14.sp else 13.5.sp),
                color = if (dashed) (if (accent) DaodianColors.current.accent else DaodianColors.current.muted) else onInk.fg
            )
            if (detail != null) Text(detail, style = DaodianType.caption, color = onInk.sub)
        }
    }
}

@Composable
private fun SolidPill(text: String, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.flow(Motion.SHORT), label = "pillPress")
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(36.dp)
            .background(colors.solid, RoundedCornerShape(18.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(text, animationSpec = tween(120), label = "pillText") {
            Text(it, style = DaodianType.bodySmall.copy(fontSize = 14.sp), color = colors.onSolid)
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
