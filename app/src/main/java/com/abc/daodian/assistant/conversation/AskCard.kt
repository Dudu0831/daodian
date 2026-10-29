package com.abc.daodian.assistant.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
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
 * 问卡：对话里唯一的卡片。模型拿不准时先猜好几个答案，你点一下；都不对就点「其他…」自己写，
 * 或者什么都不点、直接在输入框里说一句。见 DESIGN.md §6.6。
 *
 * 答完后整张卡片向上收起化入回合抬头「· 到点」右侧的伴随印，「答」印在伴随印上盖下 —— 正文区不留大块表单。
 */

/** 问卡的一题答成了什么，收起后细目里写它；null = 先放着 */
fun answerLabelOf(q: AskQuestion, pick: Pick?, single: Boolean): String? = when (pick) {
    null -> null
    is Pick.Typed -> pick.text
    is Pick.Option -> q.options.getOrNull(pick.index)?.let { o ->
        if (single && o.detail != null) "${o.label} · ${o.detail}" else o.label
    }
}

/** 一题在输入框句首垫的字：「¥219.00 是」「什么时候提醒：」 */
fun scopeLabelOf(q: AskQuestion): String = q.amount?.let { "$it 是" } ?: q.prompt?.let { "${it.trimEnd('？', '?')}：" } ?: "这一题："

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
        Asking(block, request, onPick, onOther, onSubmit)
    }
}

/** 等你答（或者参数还在流）的那张卡 */
@Composable
private fun Asking(
    block: TurnBlock.Ask,
    request: AskRequest,
    onPick: (question: Int, option: Int) -> Unit,
    onOther: (question: Int) -> Unit,
    onSubmit: () -> Unit
) {
    val colors = DaodianColors.current
    val live = block.state == AskState.READY
    val headed = request.headed()
    // 这张卡一出来就已经有的题（一次性请求）一起错峰升起；流式时每收全一题升一题
    val initialRows = remember { if (block.state == AskState.DRAFT) 0 else request.questions.size }

    Column {
        if (headed != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AskGlyph("问", colors.accent, Modifier.padding(top = 3.dp))
                Text(headed.prompt.orEmpty(), style = HeadPrompt, color = colors.ink, modifier = Modifier.weight(1f))
            }
            HeadedQuestion(headed, block.picks.firstOrNull(), live) { onPick(0, it) }
        } else {
            Row(
                Modifier.heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                AskGlyph("问", colors.accent)
                Text(
                    request.label.ifBlank { if (request.single) "问一下" else "${request.questions.size} 题" },
                    style = DaodianType.caption,
                    color = colors.ink2
                )
            }
            request.questions.forEachIndexed { i, q ->
                key(i) {
                    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                    val delayMs = if (i < initialRows) i * 60 else 0
                    AnimatedVisibility(
                        visibleState = appear,
                        enter = fadeIn(Motion.settle(delay = delayMs)) + slideInVertically(Motion.settle(delay = delayMs)) { it / 6 }
                    ) {
                        Question(
                            q = q,
                            single = request.single,
                            pick = block.picks.getOrNull(i),
                            editing = block.editing == i,
                            live = live,
                            onPick = { onPick(i, it) },
                            onOther = { onOther(i) }
                        )
                    }
                }
            }
        }

        // 卡脚只有多题的卡才有：一题的点了就算答
        AnimatedVisibility(
            visible = live && !request.single,
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

/** 卡头问句的字：宋体 16，和多题卡里没有金额的那种问句一样大 */
private val HeadPrompt = DaodianType.cardTitle.copy(fontSize = 16.sp, lineHeight = 24.sp)

/** 一题、问句写在卡头的：底下出处、依据，一排胶囊（点了就算答，没有「其他…」—— 都不是就直接说） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadedQuestion(q: AskQuestion, pick: Pick?, live: Boolean, onPick: (Int) -> Unit) {
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
                selected = selected == j, dimmed = pick != null && selected != j, enabled = live
            ) { onPick(j) }
        }
    }
    if (live && ref != null) Addon(ref)
}

private val HintText = DaodianType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp)

/** 卡里的一题：金额打头、后面是出处（没有金额的是出处 + 问句），依据，一排猜测 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Question(
    q: AskQuestion,
    single: Boolean,
    pick: Pick?,
    editing: Boolean,
    live: Boolean,
    onPick: (Int) -> Unit,
    onOther: () -> Unit
) {
    val colors = DaodianColors.current
    // 参数还在流：猜测先半透明，点不了 —— 可能还在变
    val ready by animateFloatAsState(if (live) 1f else 0.55f, Motion.flow(), label = "askReady")
    Column {
        HorizontalDivider(color = colors.ruleSoft)
        Column(Modifier.padding(top = 9.dp, bottom = 10.dp)) {
            // 问卡的类在 :runtime 里，跨模块的属性不能 smart cast，先取出来
            val amount = q.amount
            val origin = q.context
            val prompt = q.prompt
            val hint = q.hint
            val ref = q.ref
            if (amount != null) {
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
            val typed = (pick as? Pick.Typed)?.text
            FlowRow(
                Modifier.padding(top = 7.dp).graphicsLayer { alpha = ready },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                q.options.forEachIndexed { j, o ->
                    ChipLabel(o.label, selected = selected == j, dimmed = pick != null && selected != j, enabled = live) { onPick(j) }
                }
                // 点「其他…」自己写的那个，落回来就是一颗实心的
                if (typed != null) ChipLabel(typed, selected = true, dimmed = false, enabled = false) {}
                // 一题的卡（问的是一笔账）没有「其他…」：都不是就直接说
                if (!single) {
                    ChipLabel(
                        text = if (editing) "正在输入…" else "其他…",
                        selected = false, dimmed = false, enabled = live, dashed = true, accent = editing,
                        onClick = onOther
                    )
                }
            }
            if (live && ref != null) Addon(ref)
        }
    }
}

private val AmountText = DaodianType.rowTitle.copy(fontSize = 15.sp)

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
