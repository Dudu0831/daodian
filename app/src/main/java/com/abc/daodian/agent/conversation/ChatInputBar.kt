package com.abc.daodian.agent.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.ui.KeyboardIcon
import com.abc.daodian.shared.ui.MicIcon
import com.abc.daodian.shared.ui.SendIcon
import com.abc.daodian.shared.ui.StopIcon
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion

/** 「按住说话」那一块现在是哪一档 */
enum class VoiceHold {
    Idle,
    /** 按着，松手就发 */
    Holding,
    /** 按着滑上去了，松手就不要了 */
    Armed
}

/** 按着往上滑过这么远算「要取消」；滑回来到这个数以内才算反悔 —— 两个数错开，手抖不会来回跳 */
private val ARM_DISTANCE = 72.dp
private val DISARM_DISTANCE = 56.dp

/**
 * 底部那根横条。解析中整条压暗，发送键淡成空心圈里一个墨块 —— 那不只是禁用态，是「停」，
 * 按下去掐断当前这条流（见 DESIGN.md §6.2）。字在一路往外冒的时候，
 * 用户必须能喊停，否则只能干等。
 *
 * 最左边那个键在打字和说话之间换：点一下麦克风，中间的输入框换成「按住说话」，图标变成键盘，
 * 再点一下换回来（见 DESIGN.md §8.3「对话页：按住说话」）。说话那一档没有发送键 —— 松手就发；
 * 只有回合在跑的时候「停」才出来。按住时屏幕中间那个文字框不归这里画（[VoiceHoldOverlay]）。
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    voiceMode: Boolean = false,
    onToggleVoice: () -> Unit = {},
    hold: VoiceHold = VoiceHold.Idle,
    /** 手指按下去了。没权限之类开不了的，调用方自己不接就行，后面两个回调照样会来 */
    onHoldStart: () -> Unit = {},
    onHoldArm: (armed: Boolean) -> Unit = {},
    onHoldEnd: (cancelled: Boolean) -> Unit = {},
    onStop: (() -> Unit)? = null,
    placeholder: String = "说一句话……",
    /**
     * 点了问卡上某一题的「其他…」：句首垫上「¥219.00 是」，描一圈朱砂 ——
     * 这时候打的字、说的话只算那一题的答案。见 DESIGN.md §6.6
     */
    scope: String? = null
) {
    val colors = DaodianColors.current
    val canSend = text.isNotBlank() && enabled
    val shape = RoundedCornerShape(26.dp)
    val dim by animateFloatAsState(if (enabled) 1f else 0.55f, tween(Motion.SHORT), label = "inputDim")
    val edge by animateColorAsState(if (scope != null) colors.accent else colors.rule, tween(Motion.SHORT), label = "inputEdge")
    val stopping = !enabled && onStop != null

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, edge, shape)
            .padding(start = 9.dp, end = 11.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 按着的时候不让换档：手指一偏点到它，正说着的话就没了
        val toggleTint by animateColorAsState(
            if (hold == VoiceHold.Idle) colors.ink2 else colors.hint, tween(Motion.SHORT), label = "toggleTint"
        )
        // 不要按下去那块灰：默认的水波是个方的，罩在这颗小图标上像个灰框。图标自己会换，那就是回应
        val toggleSource = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { alpha = dim }
                .clickable(
                    interactionSource = toggleSource,
                    indication = null,
                    enabled = enabled && hold == VoiceHold.Idle,
                    onClick = onToggleVoice
                ),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(voiceMode, animationSpec = tween(Motion.SHORT), label = "voiceToggle") { voice ->
                if (voice) KeyboardIcon(tint = toggleTint)
                else MicIcon(size = 20.dp, tint = toggleTint, strokeWidth = 1.2.dp)
            }
        }

        AnimatedVisibility(
            visible = scope != null,
            enter = expandHorizontally(Motion.settle(Motion.SHORT)) + fadeIn(Motion.settle(Motion.SHORT)),
            exit = shrinkHorizontally(Motion.flow(Motion.SHORT)) + fadeOut(Motion.exit())
        ) {
            // 退场那几帧 scope 已经是 null 了，用最后一次的字画完
            val last = remember { arrayOfNulls<String>(1) }
            scope?.let { last[0] = it }
            Text(last[0].orEmpty(), style = DaodianType.body, color = colors.muted, modifier = Modifier.padding(start = 5.dp))
        }

        if (voiceMode) {
            HoldToTalk(
                hold = hold,
                enabled = enabled,
                onStart = onHoldStart,
                onArm = onHoldArm,
                onEnd = onHoldEnd,
                modifier = Modifier.weight(1f).padding(start = 5.dp).graphicsLayer { alpha = dim }
            )
        } else {
            Box(Modifier.weight(1f).padding(start = 5.dp, end = 8.dp).graphicsLayer { alpha = dim }) {
                if (text.isEmpty()) {
                    Crossfade(targetState = placeholder, animationSpec = tween(Motion.SHORT), label = "placeholder") {
                        Text(it, style = DaodianType.body, color = colors.hint)
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    textStyle = DaodianType.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    singleLine = false,
                    maxLines = 4,
                    // 空的时候它只有光标那么宽 —— 不撑满的话，点在占位字上根本唤不起键盘
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 稿子里空输入框的发送键也是实心带箭头 —— 空心圈只属于「解析中」那一档。
        // 没字时按钮还在，只是按不动：按钮凭空消失比按了没反应更让人发懵。
        // 压暗只加在文字和左边那个键上，不加在整条 Row 上 —— alpha 会 clamp 到 1，
        // 套在父层的话「停」再怎么提也提不回来，看着就像个禁用的按钮。
        // 说话那一档没有东西可发，这个位置空着；回合在跑时「停」照样得在
        if (!voiceMode || stopping) {
            if (voiceMode) Spacer(Modifier.width(6.dp))
            val fill by animateColorAsState(
                if (enabled) colors.solid else colors.solid.copy(alpha = 0f), tween(Motion.SHORT), label = "sendFill"
            )
            val ring by animateColorAsState(if (enabled) colors.solid else colors.rule2, tween(Motion.SHORT), label = "sendRing")
            Box(
                Modifier
                    .size(36.dp)
                    .background(fill, CircleShape)
                    .border(1.5.dp, ring, CircleShape)
                    .clickable(enabled = canSend || stopping) { if (stopping) onStop!!() else onSend() },
                contentAlignment = Alignment.Center
            ) {
                Crossfade(
                    targetState = when {
                        enabled -> SendKey.Send
                        stopping -> SendKey.Stop
                        else -> SendKey.None
                    },
                    animationSpec = tween(Motion.SHORT),
                    label = "sendKey"
                ) { key ->
                    when (key) {
                        SendKey.Send -> SendIcon(tint = if (canSend) colors.onSolid else colors.onSolid.copy(alpha = 0.45f))
                        SendKey.Stop -> StopIcon(tint = colors.ink2)
                        SendKey.None -> Unit
                    }
                }
            }
        }
    }
}

private enum class SendKey { Send, Stop, None }

/**
 * 「按住说话」那一块：按下开始，松手结束；按着往上滑过 [ARM_DISTANCE] 是要取消，滑回来接着说。
 * 手指滑出这一块之后事件照样送到这里（落点在按下那一刻就定了），所以量的是离按下那个点往上走了多远。
 */
@Composable
private fun HoldToTalk(
    hold: VoiceHold,
    enabled: Boolean,
    onStart: () -> Unit,
    onArm: (Boolean) -> Unit,
    onEnd: (cancelled: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = DaodianColors.current
    // 手势协程活得比一次重组久，回调得取最新的
    val start by rememberUpdatedState(onStart)
    val arm by rememberUpdatedState(onArm)
    val end by rememberUpdatedState(onEnd)
    val fill by animateColorAsState(
        if (hold == VoiceHold.Holding) colors.skeletonHi else colors.surfaceAlt, tween(Motion.SHORT), label = "holdFill"
    )
    val ink by animateColorAsState(
        if (hold == VoiceHold.Armed) colors.muted else colors.ink, tween(Motion.SHORT), label = "holdInk"
    )

    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val armAt = ARM_DISTANCE.toPx()
                val disarmAt = DISARM_DISTANCE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    start()
                    var armed = false
                    var ended = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val up = down.position.y - change.position.y
                            val now = up > if (armed) disarmAt else armAt
                            if (now != armed) {
                                armed = now
                                arm(now)
                            }
                        }
                        ended = true
                        end(armed)
                    } finally {
                        // 手势被掐了（页面切走、这一块被换掉）：当取消，别把说了一半的发出去
                        if (!ended) end(true)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = when (hold) {
                VoiceHold.Idle -> "按住说话"
                VoiceHold.Holding -> "松手发送"
                VoiceHold.Armed -> "滑回来接着说"
            },
            animationSpec = tween(Motion.SHORT),
            label = "holdLabel"
        ) {
            Text(it, style = HoldLabel, color = ink, maxLines = 1)
        }
    }
}

private val HoldLabel = DaodianType.rowTitle.copy(fontSize = 15.sp, letterSpacing = 0.3.em)
