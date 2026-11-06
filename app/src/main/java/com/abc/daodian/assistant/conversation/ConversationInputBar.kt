package com.abc.daodian.assistant.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.KeyboardIcon
import com.abc.daodian.shared.ui.MicIcon
import com.abc.daodian.shared.ui.PlusIcon
import com.abc.daodian.shared.ui.SendIcon
import com.abc.daodian.shared.ui.StopIcon

/** 「按住说话」那一块现在是哪一档：手指在中间、拖到左边「取消」、还是拖到右边「转文字」 */
enum class VoiceHold {
    Idle,
    /** 按着，原地松手就发 */
    Holding,
    /** 拖到左边了，松手就不要了 */
    Cancel,
    /** 拖到右边了，中间出字，松手发字 */
    Text
}

/**
 * 落点（原型 https://claude.ai/artifact/VtJp6zsX1jJJSarSxQwY3U）：手指过了屏幕左右三成算到了；
 * 按下的地方本来就靠边的，还得再往那边挪 [ZONE_MIN_TRAVEL] 才算，不然一按下就在「取消」里。
 * 回到离那条线 [ZONE_BACK] 以内的中间才算回来 —— 进出两个数错开，手抖不会来回跳
 */
private const val ZONE_EDGE = 0.3f
private val ZONE_MIN_TRAVEL = 40.dp
private val ZONE_BACK = 20.dp

/**
 * 底部那根横条。解析中整条压暗，最右那一格成了空心圈里一个墨块 —— 那不只是禁用态，是「停」，
 * 按下去掐断当前这条流（见 DESIGN.md §6.2）。字在一路往外冒的时候，用户必须能喊停，否则只能干等。
 *
 * 最左是 ＋（加图片），选好的图排在输入框上面一行（[PendingImages]，DESIGN.md §6.10）。
 * 最右那一格轮着换（11-04 定）：空着时是麦克风 / 键盘，点一下在打字和「按住说话」之间换；
 * 有东西可发换成实心的发送，回合在跑时是「停」。框里有字时没有麦克风 —— 先发掉或清空才能换档；
 * 说话那一档带着图时键盘被发送顶掉，得先发图或叉掉。
 * 按住时屏幕中间那一层不归这里画（[VoiceHoldOverlay]，DESIGN.md §8.3）。
 */
@Composable
fun ConversationInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    voiceMode: Boolean = false,
    onToggleVoice: () -> Unit = {},
    hold: VoiceHold = VoiceHold.Idle,
    /** 手指按下去了。没权限之类开不了的，调用方自己不接就行，后面两个回调照样会来 */
    onHoldStart: () -> Unit = {},
    /** 手指换了落点（[VoiceHold.Holding] / [VoiceHold.Cancel] / [VoiceHold.Text]） */
    onHoldZone: (VoiceHold) -> Unit = {},
    /** 松手了，[zone] 是松手时的落点；手势被掐了（页面切走）算 [VoiceHold.Cancel] */
    onHoldEnd: (zone: VoiceHold) -> Unit = {},
    onStop: (() -> Unit)? = null,
    /** 选好、还没发的图 */
    images: List<String> = emptyList(),
    /** 点了 ＋（最左那一格的，或者缩略图后面虚线那一格） */
    onAttach: () -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
    /** 这会儿能不能加图：回合在跑、问卡等着你答的时候不能，＋ 压暗 */
    attachEnabled: Boolean = true,
    maxImages: Int = Int.MAX_VALUE,
    placeholder: String = "说一句话……"
) {
    val colors = DaodianColors.current
    val hasImages = images.isNotEmpty()
    // 说话那一档只看图：框里的字是换档前的，这会儿看不见（一般也没有：有字换不了档）
    val canSend = ((!voiceMode && text.isNotBlank()) || hasImages) && enabled
    val shape = RoundedCornerShape(26.dp)
    val dim by animateFloatAsState(if (enabled) 1f else 0.55f, tween(Motion.SHORT), label = "inputDim")
    val stopping = !enabled && onStop != null

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .padding(start = 9.dp, end = 11.dp, top = 11.dp, bottom = 11.dp)
    ) {
        AnimatedVisibility(
            visible = hasImages,
            enter = expandVertically(Motion.flow(Motion.SHORT)) + fadeIn(Motion.flow(Motion.SHORT)),
            exit = shrinkVertically(Motion.flow(Motion.SHORT)) + fadeOut(Motion.exit())
        ) {
            // 退场那几帧 images 已经空了，用最后一次的画完
            val last = remember { arrayOfNulls<List<String>>(1) }
            if (hasImages) last[0] = images
            val shown = last[0].orEmpty()
            PendingImages(
                images = shown,
                canAdd = attachEnabled && hold == VoiceHold.Idle && shown.size < maxImages,
                onAdd = onAttach,
                onRemove = onRemoveImage,
                modifier = Modifier.padding(start = 5.dp, bottom = 10.dp).graphicsLayer { alpha = dim }
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 最左的 ＋：一根细线图标，不要按下去那块灰 —— 默认的水波是个方的，罩在这颗小图标上像个灰框。
            // 按着说话的时候不让点：手指一偏点到它，正说着的话就没了
            val canAttach = attachEnabled && hold == VoiceHold.Idle
            Box(
                Modifier
                    .size(36.dp)
                    .graphicsLayer { alpha = dim }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = canAttach,
                        onClick = onAttach
                    ),
                contentAlignment = Alignment.Center
            ) {
                val tint by animateColorAsState(if (canAttach) colors.ink2 else colors.hint, tween(Motion.SHORT), label = "plusTint")
                PlusIcon(size = 19.dp, tint = tint, strokeWidth = 1.2.dp)
            }

            if (voiceMode) {
                HoldToTalk(
                    hold = hold,
                    enabled = enabled,
                    onStart = onHoldStart,
                    onZone = onHoldZone,
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

            // 最右那一格：实心只属于发送，空心圈只属于「停」，麦克风 / 键盘和左边的 ＋ 一样只是一根细线图标。
            // 压暗只加在文字和左边那个键上，不加在整条 Row 上 —— alpha 会 clamp 到 1，
            // 套在父层的话「停」再怎么提也提不回来，看着就像个禁用的按钮。
            val key = when {
                stopping -> SendKey.Stop
                !enabled -> SendKey.None
                canSend -> SendKey.Send
                // 框里有字（只是空格之类发不出去的）也不给换档：先清空
                !voiceMode && text.isNotEmpty() -> SendKey.None
                voiceMode -> SendKey.Keyboard
                else -> SendKey.Mic
            }
            // 按着说话的时候不让换档：手指一偏点到它，正说着的话就没了
            val canToggle = hold == VoiceHold.Idle
            if (voiceMode) Spacer(Modifier.width(6.dp))
            val fill by animateColorAsState(
                if (key == SendKey.Send) colors.solid else colors.solid.copy(alpha = 0f), tween(Motion.SHORT), label = "sendFill"
            )
            val ring by animateColorAsState(
                when (key) {
                    SendKey.Send -> colors.solid
                    SendKey.Stop -> colors.rule2
                    else -> colors.rule2.copy(alpha = 0f)
                },
                tween(Motion.SHORT), label = "sendRing"
            )
            val line = key == SendKey.Mic || key == SendKey.Keyboard
            Box(
                Modifier
                    .size(36.dp)
                    .background(fill, CircleShape)
                    .border(1.5.dp, ring, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // 细线图标没有底，默认那块方的水波罩上去像个灰框；图标自己会换，那就是回应
                        indication = if (line) null else LocalIndication.current,
                        enabled = when (key) {
                            SendKey.Send, SendKey.Stop -> true
                            SendKey.Mic, SendKey.Keyboard -> canToggle
                            SendKey.None -> false
                        }
                    ) {
                        when (key) {
                            SendKey.Send -> onSend()
                            SendKey.Stop -> onStop?.invoke()
                            SendKey.Mic, SendKey.Keyboard -> onToggleVoice()
                            SendKey.None -> Unit
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val lineTint by animateColorAsState(if (canToggle) colors.ink2 else colors.hint, tween(Motion.SHORT), label = "toggleTint")
                Crossfade(targetState = key, animationSpec = tween(Motion.SHORT), label = "sendKey") { k ->
                    when (k) {
                        SendKey.Send -> SendIcon(tint = colors.onSolid)
                        SendKey.Stop -> StopIcon(tint = colors.ink2)
                        SendKey.Mic -> MicIcon(size = 20.dp, tint = lineTint, strokeWidth = 1.2.dp)
                        SendKey.Keyboard -> KeyboardIcon(tint = lineTint)
                        SendKey.None -> Unit
                    }
                }
            }
        }
    }
}

private enum class SendKey { Send, Stop, Mic, Keyboard, None }

/**
 * 「按住说话」那一块：按下开始，松手结束；按着往左拖是取消、往右拖是转文字，拖回中间照旧。
 * 手指滑出这一块之后事件照样送到这里（落点在按下那一刻就定了），所以按屏幕上的横坐标算落点。
 */
@Composable
private fun HoldToTalk(
    hold: VoiceHold,
    enabled: Boolean,
    onStart: () -> Unit,
    onZone: (VoiceHold) -> Unit,
    onEnd: (zone: VoiceHold) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = DaodianColors.current
    // 手势协程活得比一次重组久，回调得取最新的
    val start by rememberUpdatedState(onStart)
    val zoneTo by rememberUpdatedState(onZone)
    val end by rememberUpdatedState(onEnd)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // 这一块在窗口里从哪儿起：手指的坐标是相对这一块的，换成屏幕上的才好比三成线
    val left = remember { floatArrayOf(0f) }
    val fill by animateColorAsState(
        if (hold == VoiceHold.Idle) colors.surfaceAlt else colors.skeletonHi, tween(Motion.SHORT), label = "holdFill"
    )
    val ink by animateColorAsState(
        if (hold == VoiceHold.Cancel) colors.accent else colors.ink, tween(Motion.SHORT), label = "holdInk"
    )

    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .onGloballyPositioned { left[0] = it.positionInWindow().x }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val width = screenWidth.toPx()
                val travel = ZONE_MIN_TRAVEL.toPx()
                val back = ZONE_BACK.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    start()
                    val downX = left[0] + down.position.x
                    // 按下的地方本来就靠边，就从按下的地方再挪一截才算到
                    val cancelLine = minOf(width * ZONE_EDGE, downX - travel)
                    val textLine = maxOf(width * (1 - ZONE_EDGE), downX + travel)
                    var zone = VoiceHold.Holding
                    var ended = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val x = left[0] + change.position.x
                            val now = when {
                                x < cancelLine -> VoiceHold.Cancel
                                x > textLine -> VoiceHold.Text
                                x > cancelLine + back && x < textLine - back -> VoiceHold.Holding
                                else -> zone
                            }
                            if (now != zone) {
                                zone = now
                                zoneTo(now)
                            }
                        }
                        ended = true
                        end(zone)
                    } finally {
                        // 手势被掐了（页面切走、这一块被换掉）：当取消，别把说了一半的发出去
                        if (!ended) end(VoiceHold.Cancel)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = holdLabel(hold), animationSpec = tween(Motion.SHORT), label = "holdLabel") {
            Text(it, style = HoldLabel, color = ink, maxLines = 1)
        }
    }
}

/** 按着时输入条上和那一层里写的同一句 */
internal fun holdLabel(hold: VoiceHold) = when (hold) {
    VoiceHold.Idle -> "按住说话"
    VoiceHold.Holding -> "松手发送"
    VoiceHold.Text -> "松手发送文字"
    VoiceHold.Cancel -> "松手取消"
}

private val HoldLabel = DaodianType.rowTitle.copy(fontSize = 15.sp, letterSpacing = 0.3.em)
