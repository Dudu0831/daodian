package com.abc.daodian.agent.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.agent.engine.ask.AskQuestion
import com.abc.daodian.agent.engine.ask.AskUserTool
import com.abc.daodian.agent.engine.ask.Pick
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 问卡：对话里唯一的卡片。模型拿不准时先猜好几个答案，你点一下；都不对就点「其他…」自己写，
 * 或者什么都不点、直接在输入框里说一句。见 DESIGN.md §6.9，动效稿：
 * https://claude.ai/artifact/BTqaHuU6hbgjmG6NqPv5HP
 *
 * 答完原地收成一行行记录，「问」换成「答」、印盖下来 —— 整个对话里唯一的一枚印。不震动。
 */

/** 问卡的一题答成了什么，收起后那一行写它；null = 先放着 */
fun answerLabelOf(q: AskQuestion, pick: Pick?, single: Boolean): String? = when (pick) {
    null -> null
    is Pick.Typed -> pick.text
    is Pick.Option -> q.options.getOrNull(pick.index)?.let { o ->
        if (single && o.detail != null) "${o.label} · ${o.detail}" else o.label
    }
}

/** 一题在输入框句首垫的字：「¥219.00 是」「什么时候提醒：」 */
fun scopeLabelOf(q: AskQuestion): String = q.amount?.let { "$it 是" } ?: q.prompt?.let { "${it.trimEnd('？', '?')}：" } ?: "这一题："

@Composable
fun AskCard(
    block: TurnBlock.Ask,
    onPick: (question: Int, option: Int) -> Unit,
    onOther: (question: Int) -> Unit,
    onSubmit: () -> Unit
) {
    val colors = DaodianColors.current
    val request = remember(block.request, block.arguments) { block.request ?: AskUserTool.draftOf(block.arguments) }
    val live = block.state == AskState.READY
    val sealed = block.state == AskState.ANSWERED || block.state == AskState.SAID || block.state == AskState.UNANSWERED

    // 答完卡框和底色褪掉，剩下一行行记录贴着正文
    val shape = RoundedCornerShape(6.dp)
    val bg by animateColorAsState(if (sealed) colors.surface.copy(alpha = 0f) else colors.surface, Motion.flow(), label = "askBg")
    val edge by animateColorAsState(if (sealed) colors.rule.copy(alpha = 0f) else colors.rule, Motion.flow(), label = "askEdge")
    val padH by animateDpAsState(if (sealed) 0.dp else 14.dp, Motion.flow(), label = "askPadH")
    val padV by animateDpAsState(if (sealed) 2.dp else 10.dp, Motion.flow(), label = "askPadV")

    // 这张卡一出来就已经有的题（一次性请求、重启读回）一起错峰升起；流式时每收全一题升一题
    val animateRows = remember { !sealed }
    val initialRows = remember { if (block.state == AskState.DRAFT) 0 else request.questions.size }

    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, shape)
            .border(1.dp, edge, shape)
            .padding(horizontal = padH, vertical = padV)
            .animateContentSize(Motion.flow())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AskSeal(
                answered = block.state == AskState.ANSWERED || block.state == AskState.SAID,
                muted = block.state == AskState.UNANSWERED,
                fresh = System.currentTimeMillis() - block.answeredAt < 1_500
            )
            Text(
                request.label.ifBlank { if (request.single) "问一下" else "${request.questions.size} 题" },
                style = DaodianType.caption,
                color = if (sealed) colors.muted else colors.ink2
            )
        }

        when (block.state) {
            AskState.SAID -> RecordNote("没点 · 你直接说了")
            AskState.UNANSWERED -> RecordNote("没答")
            else -> request.questions.forEachIndexed { i, q ->
                key(i) {
                    val appear = remember { MutableTransitionState(!animateRows).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = appear,
                        enter = fadeIn(Motion.settle(delay = if (i < initialRows) i * 60 else 0)) +
                            slideInVertically(Motion.settle(delay = if (i < initialRows) i * 60 else 0)) { it / 6 }
                    ) {
                        Column {
                            if (i > 0 && !sealed) HorizontalDivider(color = colors.ruleSoft)
                            Crossfade(sealed, animationSpec = tween(Motion.MID), label = "askRow") { done ->
                                if (done) {
                                    RecordLine(q, answerLabelOf(q, block.picks.getOrNull(i), request.single), request.single)
                                } else {
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
            Row(
                Modifier.padding(top = 12.dp, bottom = 2.dp),
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

/** 还没答的一题：出处 + 金额 / 问句，依据，一排猜测 */
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
    Column(Modifier.padding(top = if (single) 6.dp else 11.dp, bottom = if (single) 2.dp else 12.dp)) {
        if (q.amount != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    q.context.orEmpty(), style = DaodianType.caption, color = colors.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text(q.amount, style = DaodianType.rowTitle.copy(fontSize = 16.sp), color = colors.ink)
            }
        } else if (q.context != null) {
            Text(q.context, style = DaodianType.caption, color = colors.muted)
        }
        if (q.prompt != null && (single || q.amount == null)) {
            Text(
                q.prompt,
                style = DaodianType.cardTitle.copy(fontSize = if (single) 18.5.sp else 16.sp, lineHeight = 26.sp),
                color = colors.ink
            )
        }
        if (q.hint != null) {
            Text(q.hint, style = DaodianType.caption.copy(fontSize = 12.sp, lineHeight = 17.sp), color = colors.muted, modifier = Modifier.padding(top = 3.dp))
        }

        val selected = (pick as? Pick.Option)?.index
        val typed = (pick as? Pick.Typed)?.text
        if (single) {
            Column(
                Modifier.padding(top = 10.dp).graphicsLayer { alpha = ready },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                q.options.forEachIndexed { j, o ->
                    InkChip(
                        selected = selected == j,
                        dimmed = pick != null && selected != j,
                        enabled = live,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 50.dp),
                        onClick = { onPick(j) }
                    ) { onInk ->
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(o.label, style = DaodianType.body, color = onInk.fg, modifier = Modifier.weight(1f))
                            o.detail?.let { Text(it, style = DaodianType.caption, color = onInk.sub) }
                        }
                    }
                }
            }
        } else {
            FlowRow(
                Modifier.padding(top = 9.dp).graphicsLayer { alpha = ready },
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                q.options.forEachIndexed { j, o ->
                    ChipLabel(o.label, selected = selected == j, dimmed = pick != null && selected != j, enabled = live) { onPick(j) }
                }
                // 点「其他…」自己写的那个，落回来就是一颗实心的
                if (typed != null) ChipLabel(typed, selected = true, dimmed = false, enabled = false) {}
                ChipLabel("其他…", selected = false, dimmed = false, enabled = live, dashed = true, accent = editing, onClick = onOther)
            }
        }
    }
}

/**
 * 收起后的一题。问账的：左边宋体金额，右边答案。没有金额的：淡色问句接着答案，排成一行，
 * 问句太长就让它折行 —— 截成「下周哪天…」看不出问的是什么。没点的写「先放着」
 */
@Composable
private fun RecordLine(q: AskQuestion, answer: String?, single: Boolean) {
    val colors = DaodianColors.current
    val style = DaodianType.bodySmall.copy(fontSize = 14.sp, lineHeight = 23.sp)
    val shown = answer ?: "先放着"
    val tone = if (answer == null) colors.muted else colors.ink2
    when {
        single -> Text(shown, style = style, color = tone, modifier = Modifier.padding(vertical = 1.dp))
        q.amount != null -> Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                q.amount, style = DaodianType.rowTitle.copy(fontSize = 14.sp, lineHeight = 23.sp),
                color = colors.muted, maxLines = 1, modifier = Modifier.width(76.dp)
            )
            Text(shown, style = style, color = tone)
        }
        else -> Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.muted)) { append(q.prompt ?: q.context.orEmpty()) }
                append("  ")
                withStyle(SpanStyle(color = tone)) { append(shown) }
            },
            style = style,
            modifier = Modifier.padding(vertical = 1.dp)
        )
    }
}

@Composable
private fun RecordNote(text: String) {
    Text(text, style = DaodianType.bodySmall.copy(fontSize = 14.sp, lineHeight = 23.sp), color = DaodianColors.current.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ChipLabel(
    text: String,
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    dashed: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    InkChip(
        selected = selected, dimmed = dimmed, enabled = enabled, dashed = dashed, accent = accent,
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.height(34.dp),
        onClick = onClick
    ) { onInk ->
        Text(
            text,
            style = DaodianType.bodySmall.copy(fontSize = 14.sp),
            color = if (dashed) (if (accent) DaodianColors.current.accent else DaodianColors.current.muted) else onInk.fg,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

/** 猜测上的字色：墨色洇满之后翻成纸色 */
private class InkTone(val fg: Color, val sub: Color)

/**
 * 一颗猜测。按下缩到 0.96；选中时墨色以手指落下的地方为圆心洇满整颗，取消时原路退回。
 * 同一题别的猜测淡到一半（[dimmed]），还能改。
 */
@Composable
private fun InkChip(
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
    content: @Composable (InkTone) -> Unit
) {
    val colors = DaodianColors.current
    val interaction = remember { MutableInteractionSource() }
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Press) origin = it.pressPosition }
    }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.flow(Motion.SHORT), label = "chipPress")
    val alpha by animateFloatAsState(if (dimmed) 0.5f else 1f, Motion.flow(), label = "chipDim")
    val fill = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { fill.animateTo(if (selected) 1f else 0f, if (selected) Motion.settle() else Motion.flow()) }
    val fg by animateColorAsState(if (selected) colors.onSolid else colors.ink, Motion.flow(), label = "chipFg")
    val sub by animateColorAsState(if (selected) colors.onSolid.copy(alpha = 0.72f) else colors.muted, Motion.flow(), label = "chipSub")
    val edge by animateColorAsState(
        when {
            selected -> colors.solid
            accent -> colors.accent
            else -> colors.rule2
        },
        Motion.flow(Motion.SHORT), label = "chipEdge"
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(shape)
            .drawBehind {
                val f = fill.value
                if (f > 0f) {
                    val o = if (origin.isSpecified) origin else center
                    val reach = hypot(max(o.x, size.width - o.x), max(o.y, size.height - o.y))
                    drawCircle(colors.solid, radius = reach * f, center = o)
                }
            }
            .then(if (dashed) Modifier.dashedBorder(edge, size = 17.dp) else Modifier.border(1.dp, edge, shape))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        content(InkTone(fg, sub))
    }
}

/** 「其他…」那颗的虚线边 */
private fun Modifier.dashedBorder(color: Color, size: Dp): Modifier = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(this.size.width - w, this.size.height - w),
        cornerRadius = CornerRadius(size.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    )
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
            .height(40.dp)
            .background(colors.solid, RoundedCornerShape(20.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(text, animationSpec = tween(120), label = "pillText") {
            Text(it, style = DaodianType.bodySmall.copy(fontSize = 14.5.sp), color = colors.onSolid)
        }
    }
}

/**
 * 卡头那枚印：没答是「问」，答了换成「答」盖下来 —— 从 1.45 倍、−10° 带回弹落下，印边洇开一圈。
 * 原来盖在「已记下」上的就是这一枚，挪到了你答完的那一刻。没答的是灰的。
 */
@Composable
private fun AskSeal(answered: Boolean, muted: Boolean, fresh: Boolean) {
    val colors = DaodianColors.current
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Crossfade(answered, animationSpec = tween(Motion.SHORT), label = "askSeal") { done ->
            if (done) StampGlyph("答", fresh) else Glyph("问", if (muted) colors.hint else colors.accent)
        }
    }
}

@Composable
private fun Glyph(text: String, tint: Color) {
    Box(Modifier.size(20.dp).border(1.dp, tint, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        Text(text, style = DaodianType.seal.copy(fontSize = 11.5.sp, lineHeight = 12.sp), color = tint)
    }
}

@Composable
private fun StampGlyph(text: String, fresh: Boolean) {
    val colors = DaodianColors.current
    val scale = remember { Animatable(if (fresh) 1.45f else 1f) }
    val rotation = remember { Animatable(if (fresh) -10f else 0f) }
    val alpha = remember { Animatable(if (fresh) 0f else 1f) }
    val bleed = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (!fresh) return@LaunchedEffect
        bleed.snapTo(0f)
        launch { scale.animateTo(1f, Motion.stamp()) }
        launch { rotation.animateTo(0f, Motion.stamp()) }
        launch { alpha.animateTo(1f, tween(Motion.SHORT)) }
        launch {
            delay(200)
            bleed.animateTo(1f, tween(640, easing = LinearOutSlowInEasing))
        }
    }
    Box(
        Modifier
            .size(20.dp)
            .drawBehind {
                val b = bleed.value
                if (b < 1f) {
                    val grow = 7.dp.toPx() * b
                    drawRoundRect(
                        colors.accent.copy(alpha = 0.4f * (1f - b)),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(3.dp.toPx() + grow),
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
        Glyph(text, colors.accent)
    }
}
