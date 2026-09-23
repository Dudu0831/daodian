package com.abc.daodian.agent.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion

/** 助手说的话一律缩进这么多，跟上面的「· 到点」标签对齐 */
private val AssistantIndent = 14.dp

/** 刚发出的气泡从下方升起；列表滚回来重组时不再升 */
@Composable
fun UserBubble(msg: ChatMessage.UserText) = UserBubble(msg.text, msg.sentAt)

@Composable
fun UserBubble(text: String, sentAt: Long) {
    val colors = DaodianColors.current
    val rise = remember { Animatable(if (System.currentTimeMillis() - sentAt < 600) 0f else 1f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, Motion.settle()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            style = DaodianType.body,
            color = colors.onSolid,
            modifier = Modifier
                .graphicsLayer {
                    val p = rise.value
                    translationY = (1f - p) * 26.dp.toPx()
                    alpha = 0.15f + 0.85f * p
                    scaleX = 0.97f + 0.03f * p
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(1f, 1f)
                }
                .widthIn(max = 264.dp)
                .background(colors.solid, RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

/**
 * 朱砂小圆点 + 「到点」。等回复的时候圆点呼吸，告诉你是谁在准备说话；
 * 出错时褪成灰的 —— 报错不该比正常回答更抢眼
 */
@Composable
fun SpeakerTag(isError: Boolean = false, breathing: Boolean = false) {
    val colors = DaodianColors.current
    val dot by animateColorAsState(if (isError) colors.rule2 else colors.accent, Motion.flow(), label = "speakerDot")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (breathing) BreathingDot(dot) else Box(Modifier.size(5.dp).background(dot, CircleShape))
        Text("到点", style = DaodianType.speakerTag, color = colors.muted)
    }
}

@Composable
private fun BreathingDot(color: Color) {
    val a by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breatheAlpha"
    )
    Box(Modifier.size(5.dp).graphicsLayer { alpha = a }.background(color, CircleShape))
}

/** 回合里能点的东西。对话页全接；桌面速记只接它用得上的几样，其余默认什么都不做 */
interface TurnActions {
    fun toggleReasoning() {}
    fun toggleTrace(callId: String) {}
    /** 点了痕：去它指向的那条（路由） */
    fun openTrace(route: String) {}
    fun pick(callId: String, question: Int, option: Int) {}
    fun other(callId: String, question: Int) {}
    fun submit(callId: String) {}
    fun manualAdd() {}
    fun retry() {}
}

/**
 * 助手的一个回合。见 DESIGN.md §6.7、§6.9。
 *
 * 块按到货顺序摞：正文、痕、问卡，每一块都是展开着进场。还在跑、眼下又没东西在出的时候，
 * 末尾画两根墨条（刚发出去、工具跑完等它接着说、问卡答完等它接着办）。
 * 问卡等着时你直接说了一句：那句话画成你的气泡，后面的回应另起一个「· 到点」。
 */
@Composable
fun AssistantTurnRow(msg: ChatMessage.AssistantTurn, actions: TurnActions) {
    val colors = DaodianColors.current
    // 按「你直接说的话」切段：每段自己一个「· 到点」
    val segments = remember(msg.blocks) {
        val out = mutableListOf<Pair<TurnBlock.Said?, List<TurnBlock>>>(null to mutableListOf())
        msg.visibleBlocks.forEach { b ->
            if (b is TurnBlock.Said) out += b to mutableListOf() else (out.last().second as MutableList).add(b)
        }
        out
    }
    // 重启读回来的回合不再一块块长出来
    val animate = msg.startedAt != 0L

    Column {
        segments.forEachIndexed { si, (said, blocks) ->
            val last = si == segments.lastIndex
            if (said != null) {
                Spacer(Modifier.height(24.dp))
                UserBubble(said.text, said.sentAt)
                Spacer(Modifier.height(24.dp))
            }
            SpeakerTag(isError = msg.isError, breathing = last && msg.waiting)
            Column(Modifier.padding(start = AssistantIndent)) {
                if (si == 0) {
                    Reveal(msg.waiting && msg.fellBack && msg.blocks.isEmpty()) {
                        Text(
                            "换个方式重新问了一次",
                            style = DaodianType.speakerTag.copy(fontSize = 11.5.sp, letterSpacing = 0.1.em),
                            color = colors.hint
                        )
                    }
                    Reveal(msg.reasoning.isNotBlank() && !msg.reasoningFolded) { ReasoningStream(msg.reasoning) }
                    Reveal(msg.reasoning.isNotBlank() && msg.reasoningFolded) {
                        ReasoningTrace(
                            msg.reasoning,
                            seconds = ((msg.thoughtMillis ?: 0L) / 1000).toInt(),
                            expanded = msg.reasoningOpen,
                            onToggle = actions::toggleReasoning
                        )
                    }
                }

                blocks.forEach { b ->
                    key(b.key) {
                        Appear(animate, enter = if (b is TurnBlock.Ask) CardEnter else RevealEnter) {
                            when (b) {
                                is TurnBlock.Prose -> {
                                    val streaming = msg.streaming && b.key == msg.blocks.lastOrNull()?.key
                                    InkText(b.text, streaming = streaming, style = DaodianType.prose, color = colors.ink, caret = streaming)
                                }
                                is TurnBlock.Trace -> TraceLine(
                                    b,
                                    onOpen = actions::openTrace,
                                    onToggle = { actions.toggleTrace(b.callId) }
                                )
                                is TurnBlock.Ask -> AskCard(
                                    b,
                                    onPick = { q, o -> actions.pick(b.callId, q, o) },
                                    onOther = { q -> actions.other(b.callId, q) },
                                    onSubmit = { actions.submit(b.callId) }
                                )
                                is TurnBlock.Said -> Unit
                            }
                        }
                    }
                }

                // 墨条收起和下一块内容进场在同一帧，中间没有空白帧
                if (last) Reveal(msg.waiting, exit = BarsExit) { InkWashBars() }

                if (si == 0) {
                    Reveal(msg.isError) {
                        Column {
                            Text("你可以自己填一条，跟解析出来的一样能用。", style = DaodianType.prose, color = colors.muted)
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                PillButton("手动填一条", PillStyle.OutlineStrong, actions::manualAdd)
                                PillButton("重试", PillStyle.Outline, actions::retry)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一块内容第一次出现时长出来；[animate] 为 false（重启读回的）就直接在那儿 */
@Composable
private fun ColumnScope.Appear(animate: Boolean, enter: EnterTransition, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(!animate).apply { targetState = true } }
    AnimatedVisibility(visibleState = state, enter = enter, exit = RevealExit) {
        Box(Modifier.padding(top = 12.dp)) { content() }
    }
}

private val RevealEnter: EnterTransition =
    expandVertically(Motion.settle(), expandFrom = Alignment.Top) + fadeIn(Motion.flow())
private val RevealExit: ExitTransition =
    shrinkVertically(Motion.flow(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
private val BarsExit: ExitTransition =
    shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())
private val CardEnter: EnterTransition =
    expandVertically(Motion.settle(Motion.LONG), expandFrom = Alignment.Top) + fadeIn(Motion.flow())

/** 回合里的一块：块与块的间距长在块自己身上，收起时一起收，不会留一截空白 */
@Composable
private fun ColumnScope.Reveal(
    visible: Boolean,
    enter: EnterTransition = RevealEnter,
    exit: ExitTransition = RevealExit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(visible, enter = enter, exit = exit) {
        Box(Modifier.padding(top = 12.dp)) { content() }
    }
}

/**
 * 思考过程流着的时候：左边一根细线，字压到最轻的一档。
 * 最多露三行，更早的行在顶上渐隐 —— 思考不该把下面的内容越推越远。
 */
@Composable
private fun ReasoningStream(text: String) {
    val colors = DaodianColors.current
    var overflow by remember { mutableStateOf(false) }
    Box(
        Modifier
            .drawBehind { drawLine(colors.rule, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .padding(start = 12.dp)
            .heightIn(max = 60.dp)
            .clipToBounds()
            .fadeTop(24.dp, overflow)
    ) {
        InkText(
            text,
            streaming = true,
            style = DaodianType.thinkingNote.copy(lineHeight = 20.sp),
            color = colors.muted,
            caret = true,
            modifier = Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
            onTextLayout = { overflow = it.lineCount > 3 }
        )
    }
}

/**
 * 流完之后折成一行「想了 3 秒 · 看看」。不直接删：
 * 模型把时间算歪的时候，这段和卡片上的「依据」是仅有的两条线索。
 */
@Composable
private fun ReasoningTrace(text: String, seconds: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = DaodianColors.current
    Column {
        Row(
            Modifier.clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(if (seconds > 0) "想了 $seconds 秒" else "想了一下", style = DaodianType.speakerTag, color = colors.hint)
            Text(if (expanded) "收起" else "看看", style = DaodianType.speakerTag, color = colors.accent)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.flow()) + fadeIn(Motion.flow()),
            exit = shrinkVertically(Motion.flow()) + fadeOut(Motion.exit())
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .drawBehind { drawLine(colors.rule, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                        .padding(start = 12.dp)
                ) {
                    Text(text, style = DaodianType.thinkingNote, color = colors.muted)
                }
            }
        }
    }
}

internal enum class PillStyle { Solid, Outline, OutlineStrong }

@Composable
internal fun PillButton(text: String, style: PillStyle, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .let { if (style == PillStyle.Solid) it.background(colors.solid, shape) else it }
            .let {
                when (style) {
                    PillStyle.Solid -> it
                    PillStyle.Outline -> it.border(1.dp, colors.rule2, shape)
                    PillStyle.OutlineStrong -> it.border(1.dp, colors.ink, shape)
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = DaodianType.bodySmall,
            color = when (style) {
                PillStyle.Solid -> colors.onSolid
                PillStyle.OutlineStrong -> colors.ink
                PillStyle.Outline -> colors.ink2
            }
        )
    }
}

/** app 自己发起的一轮（每晚对账）：不是用户说的话，画成一条分隔线 */
@Composable
fun TriggerDivider(msg: ChatMessage.UserText) {
    val colors = DaodianColors.current
    // 开场白都是「每晚对账：……」这样，冒号前一段就是标签
    val label = msg.text.substringBefore('：').take(12)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.rule)
        Text(
            (if (msg.sentAt > 0) Format.clock(msg.sentAt) + " · " else "") + label,
            style = DaodianType.speakerTag, color = colors.hint
        )
        HorizontalDivider(Modifier.weight(1f), color = colors.rule)
    }
}
