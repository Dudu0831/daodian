package com.abc.daodian.assistant.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.runtime.model.ApiState
import com.abc.daodian.runtime.model.Effort
import com.abc.daodian.runtime.model.ProviderProfile
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import kotlin.math.roundToInt

/**
 * 顶栏那枚朱砂小印。见 DESIGN.md 决策 8.4
 *
 * 印在右上角（左上角让给了抽屉键）。印泥浓淡是**想多久**：空印是快答，满印是深想；点一下长成胶囊，在里面滑着选（[SealTrack]）。
 * 印的边框是模型服务的状态：朱砂好着、墨灰连不上、虚线空印没接模型。没接模型时点一下直接去配置页，不出轨道。
 * **后台有 agent 在跑时**（比如整理账目，见 [AgentRuntime]）印慢慢一明一暗 —— 这时候对话里改账会等它写完再动手（账本锁）。
 *
 * 以前点开是一张纸签（模型名、后台在跑什么、「改配置」），11-20 拿掉了：去配置页走抽屉 → 设置 → 模型服务，
 * 连不上时轨道旁边压一条告警带，点它也到配置页。
 */
internal enum class SealLook { Ok, Down, Unset }

@Composable
fun ProviderSeal(
    profile: ProviderProfile,
    api: ApiState,
    running: List<AgentRuntime.Running>,
    track: SealTrack,
    /** 这一轮实际在用的、和选的不一样时（画卡片那几轮至少多想）：印照它显示 */
    inUse: Effort?,
    onOpenProvider: () -> Unit
) {
    val look = lookOf(profile, api)
    SideEffect { track.chosen = profile.effort }
    val shown = inUse ?: track.shown
    // 胶囊开着时顶栏这枚藏起来，覆盖层画胶囊里那枚
    val inHand = track.phase != SealTrack.Phase.Idle
    val unset = look == SealLook.Unset
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, tween(80), label = "sealPress")

    Box(
        Modifier
            .size(44.dp)
            .onGloballyPositioned {
                track.seal = it
                track.relayout()
            }
            .then(
                if (unset) {
                    Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 22.dp),
                            onClick = onOpenProvider
                        )
                        .semantics { contentDescription = "还没接模型，去配置" }
                } else {
                    // 关着时只能点：点开胶囊。按住拉要先点开（§8.4）
                    Modifier
                        .pointerInput(track) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    tryAwaitRelease()
                                    pressed = false
                                },
                                onTap = { track.open() }
                            )
                        }
                        .semantics {
                            contentDescription = "想多久：${Ink.names[shown.ordinal]}"
                            progressBarRangeInfo = ProgressBarRangeInfo(track.shown.ordinal.toFloat(), 0f..4f, steps = 3)
                            setProgress { v ->
                                track.pickNow(v.roundToInt())
                                true
                            }
                        }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Seal(
            look = look,
            ink = Ink.of(shown),
            busy = running.isNotEmpty() && !inHand,
            pressed = press,
            stamps = track.stamps,
            modifier = Modifier.graphicsLayer { alpha = if (inHand) 0f else 1f }
        )
    }
}

internal fun lookOf(profile: ProviderProfile, api: ApiState): SealLook = when {
    !profile.isConfigured -> SealLook.Unset
    api is ApiState.Down -> SealLook.Down
    else -> SealLook.Ok          // 未知当好的：这次打开还没说过话，不该先摆一张臭脸
}

@Composable
private fun Seal(look: SealLook, ink: Float, busy: Boolean, pressed: Float, stamps: Int, modifier: Modifier) {
    val colors = DaodianColors.current
    val target = when (look) {
        SealLook.Ok -> colors.accent
        SealLook.Down -> colors.muted
        SealLook.Unset -> colors.hint
    }
    val tint by animateColorAsState(target, Motion.flow(Motion.LONG), label = "sealTint")
    val shownInk by animateFloatAsState(if (look == SealLook.Unset) 0f else ink, Motion.flow(), label = "sealInk")

    // 后台在跑：印慢慢一明一暗。停了就回到实的
    val blink by rememberInfiniteTransition(label = "sealBlink").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sealBlinkAlpha"
    )
    val busyAmount by animateFloatAsState(if (busy) 1f else 0f, Motion.flow(Motion.LONG), label = "sealBusy")

    // 盖一下：墨灰盖回朱砂（1.3 倍），换了想多久（1.15 倍）。变灰时不动 —— 坏消息不值得一个重拍
    val stamp = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(look) }
    LaunchedEffect(look) {
        if (previous != SealLook.Ok && look == SealLook.Ok) {
            stamp.snapTo(1.3f)
            stamp.animateTo(1f, Motion.stamp())
        }
        previous = look
    }
    var stamped by remember { mutableStateOf(stamps) }
    LaunchedEffect(stamps) {
        if (stamps != stamped) {
            stamped = stamps
            stamp.snapTo(1.15f)
            stamp.animateTo(1f, Motion.stamp())
        }
    }

    SealFace(
        tint = tint,
        ink = shownInk,
        bleed = Ink.bleed(levelOf(shownInk)),
        shadow = false,
        dashed = look == SealLook.Unset,
        modifier = modifier.graphicsLayer {
            val s = stamp.value * pressed
            scaleX = s
            scaleY = s
            rotationZ = (stamp.value - 1f) * -26f      // 1.3 倍时约 −8°
            alpha = 1f - (1f - blink) * busyAmount
        }
    )
}

/** 浓淡反推档位，只用来算晕圈 */
private fun levelOf(ink: Float): Float = when {
    ink <= 0.70f -> if (ink <= 0.32f) 0f else 3f * (ink - 0.32f) / (0.70f - 0.32f)
    else -> 3f + (ink - 0.70f) / 0.30f
}

/**
 * 印本身：描边方框里一个「点」字，印泥浓淡 [ink]（0 空印、1 满印）。徽标描边、圆角 3dp，见 §8.1 第 3 条。
 * 浓过一半，「点」字反白。[bleed] 是印边晕开多少 dp（多想、深想），[shadow] 是拿在手里时的影子。
 */
@Composable
internal fun SealFace(
    tint: Color,
    ink: Float,
    bleed: Float,
    shadow: Boolean,
    modifier: Modifier = Modifier,
    dashed: Boolean = false
) {
    val colors = DaodianColors.current
    val fill = lerp(colors.paper, tint, ink)
    Box(
        modifier
            .size(22.dp)
            .drawBehind {
                val r = CornerRadius(3.dp.toPx())
                if (shadow) {
                    for (k in 1..4) {
                        val o = k * 1.dp.toPx()
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.035f),
                            Offset(-o / 2, o), Size(size.width + o, size.height + o / 2), CornerRadius(3.dp.toPx() + o)
                        )
                    }
                }
                if (bleed > 0f) {
                    val steps = 4
                    for (k in steps downTo 1) {
                        val o = bleed.dp.toPx() * k / steps
                        drawRoundRect(
                            tint.copy(alpha = 0.10f),
                            Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o), CornerRadius(r.x + o)
                        )
                    }
                }
                drawRoundRect(fill, cornerRadius = r)
                if (dashed) {
                    drawRoundRect(
                        tint, cornerRadius = r,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))
                        )
                    )
                }
            }
            .then(if (dashed) Modifier else Modifier.border(1.dp, tint, RoundedCornerShape(3.dp))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "点",
            style = DaodianType.seal,
            color = when {
                dashed -> tint.copy(alpha = 0.7f)
                ink >= 0.55f -> colors.paper
                else -> tint
            }
        )
    }
}
