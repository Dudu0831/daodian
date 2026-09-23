package com.abc.daodian.agent.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.agent.model.provider.ApiState
import com.abc.daodian.agent.model.provider.ProviderProfile
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.IconTapTarget
import kotlinx.coroutines.delay

/**
 * 顶栏那枚朱砂小印，和点开它垂下来的那条纸签。见 DESIGN.md 决策 8.4
 *
 * 印在右上角（左上角让给了抽屉键）。**后台有 agent 在跑时**（比如整理账目，见
 * [AgentActivity]），印外面绕一圈慢慢转的细线，纸签上多一行「后台在整理账目 · 12 秒」——
 * 这时候对话里改账会等它写完再动手（账本锁），转圈就是在告诉你为什么。
 *
 * 印章自己带状态（朱砂 / 墨灰 / 虚线空印），纸签写细节。**好着的时候纸签上不写「状态」** ——
 * 印是朱砂就已经说明了，再写一行「连得上」是废话，而且那行字左边一个点右边三个字，右侧永远空着，
 * 怎么排都难看（2026-09-13 用户反馈）。出问题时顶上才压一条告警带。
 */
private enum class SealLook { Ok, Down, Unset }

@Composable
fun ProviderSeal(
    profile: ProviderProfile,
    api: ApiState,
    running: List<AgentActivity.Running>,
    onOpenProvider: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val look = when {
        !profile.isConfigured -> SealLook.Unset
        api is ApiState.Down -> SealLook.Down
        else -> SealLook.Ok          // 未知当好的：这次打开还没说过话，不该先摆一张臭脸
    }

    Box {
        IconTapTarget(onClick = { open = true }) { Seal(look, busy = running.isNotEmpty()) }
        if (open) {
            ProviderSheet(
                profile = profile,
                api = api,
                running = running,
                onDismiss = { open = false },
                onOpenProvider = { open = false; onOpenProvider() }
            )
        }
    }
}

/** 描边方框里一个「点」字。徽标描边不填色、圆角 3dp，见 §8.1 第 3 条 */
@Composable
private fun Seal(look: SealLook, busy: Boolean) {
    val colors = DaodianColors.current
    // 后台在跑：印外面一道 3/4 圈的细线慢慢转。停了就淡掉
    val ring by animateFloatAsState(if (busy) 1f else 0f, Motion.flow(Motion.LONG), label = "sealRing")
    val spin by rememberInfiniteTransition(label = "sealSpin").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "sealSpinAngle"
    )
    val target = when (look) {
        SealLook.Ok -> colors.accent
        SealLook.Down -> colors.muted
        SealLook.Unset -> colors.hint
    }
    val tint by animateColorAsState(target, Motion.flow(Motion.LONG), label = "sealTint")

    // 墨灰盖回朱砂：照卡片落印的样子重新盖一下，只盖一次。变灰时不动 —— 坏消息不值得一个重拍
    val stamp = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(look) }
    LaunchedEffect(look) {
        if (previous != SealLook.Ok && look == SealLook.Ok) {
            stamp.snapTo(1.3f)
            stamp.animateTo(1f, Motion.stamp())
        }
        previous = look
    }

    val dashed = look == SealLook.Unset
    val density = LocalDensity.current
    Box(
        Modifier
            .drawBehind {
                if (ring > 0f) {
                    val pad = 5.dp.toPx()
                    rotate(if (busy) spin else 0f) {
                        drawArc(
                            color = colors.accent.copy(alpha = 0.55f * ring),
                            startAngle = 0f, sweepAngle = 270f, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(-pad, -pad),
                            size = androidx.compose.ui.geometry.Size(size.width + 2 * pad, size.height + 2 * pad),
                            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
            .size(22.dp)
            .graphicsLayer {
                scaleX = stamp.value
                scaleY = stamp.value
                rotationZ = (stamp.value - 1f) * -26f      // 1.3 倍时约 −8°
            }
            .then(
                if (dashed) {
                    Modifier.drawBehind {
                        val stroke = with(density) { 1.dp.toPx() }
                        drawRoundRect(
                            color = tint,
                            cornerRadius = CornerRadius(with(density) { 3.dp.toPx() }),
                            style = Stroke(
                                width = stroke,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 2.5.dp.toPx() })
                                )
                            )
                        )
                    }
                } else {
                    Modifier.border(1.dp, tint, RoundedCornerShape(3.dp))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("点", style = DaodianType.seal, color = if (dashed) tint.copy(alpha = 0.7f) else tint)
    }
}

/**
 * 纸签：贴着屏幕左右各留 12dp 垂下来。三段 —— 出问题时的告警带、模型名片、末行「改配置」。
 * 末行的字一直是「改配置」，不跟着状态换说法：这一行是去处，不是状态的第二遍复述。
 */
@Composable
private fun ProviderSheet(
    profile: ProviderProfile,
    api: ApiState,
    running: List<AgentActivity.Running>,
    onDismiss: () -> Unit,
    onOpenProvider: () -> Unit
) {
    val colors = DaodianColors.current
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val shape = RoundedCornerShape(10.dp)

    // 印在顶栏右端（ChatTopBar 右边留 14dp），纸签右边要到 12dp，所以往右挪 2dp
    val offset = with(density) { IntOffset(x = 2.dp.roundToPx(), y = 40.dp.roundToPx()) }

    val down = api as? ApiState.Down
    val unset = !profile.isConfigured
    val bandColor = when {
        unset -> colors.surfaceAlt
        down != null -> colors.red.copy(alpha = 0.10f)
        else -> colors.surface
    }

    Popup(
        alignment = Alignment.TopEnd,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(Modifier.width(screenWidth - 24.dp)) {
            Pointer(fill = bandColor, edge = colors.rule2)
            Column(
                Modifier
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(colors.surface)
                    .border(1.dp, colors.rule2, shape)
            ) {
                // 告警带：好着的时候整条不存在。没消息就是好消息
                if (unset || down != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(bandColor)
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) {
                        Text(
                            if (unset) "还没接模型" else "连不上 —— ${down!!.why}",
                            style = DaodianType.rowTitle,
                            color = if (unset) colors.ink else colors.red
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (unset) "说的话会直接转成手动填写" else down!!.raw,
                            style = if (unset) DaodianType.caption else DaodianType.basis,
                            color = colors.muted,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(
                        color = if (unset) colors.rule else colors.red.copy(alpha = 0.22f)
                    )
                }

                // 名片：模型名是这张纸签上最大的字 —— 你点开它，多半就是想确认这个
                Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                    Text(
                        profile.model.ifBlank { "还没填模型" },
                        style = DaodianType.cardTitle,
                        color = if (profile.model.isBlank()) colors.hint else colors.ink
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        Format.host(profile.baseUrl).ifBlank { "还没填网关地址" },
                        style = DaodianType.toolName,
                        color = colors.muted
                    )
                }

                // 后台在跑的 agent：一个一行，秒数自己走
                running.forEach { r ->
                    HorizontalDivider(color = colors.ruleSoft)
                    RunningLine(r)
                }

                HorizontalDivider(color = colors.ruleSoft)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProvider)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("改配置", style = DaodianType.bodySmall, color = colors.ink2)
                    ChevronRightIcon(size = 14.dp, tint = colors.muted)
                }
            }
        }
    }
}

/** 「后台在整理账目 · 12 秒」，前面一个呼吸的朱砂点 */
@Composable
private fun RunningLine(r: AgentActivity.Running) {
    val colors = DaodianColors.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val a by rememberInfiniteTransition(label = "runDot").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "runDotAlpha"
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(5.dp).graphicsLayer { alpha = a }.background(colors.accent, CircleShape))
        Text("后台在${r.label}", style = DaodianType.bodySmall, color = colors.ink2, modifier = Modifier.weight(1f))
        Text("${((now - r.startedAt) / 1000).coerceAtLeast(0)} 秒", style = DaodianType.toolName, color = colors.muted)
    }
}

/** 纸签顶上那个指回印章的小尖角。有告警带时跟着一起变色，不然接缝处会露出一截纸色 */
@Composable
private fun Pointer(fill: Color, edge: Color) {
    // 印在右端：尖角对准印的中心（44dp 点按区的一半，减去尖角半宽，再减纸签往右挪的 2dp）
    Box(Modifier.fillMaxWidth().padding(end = 13.dp), contentAlignment = Alignment.CenterEnd) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 14.dp, height = 7.dp)) {
            val body = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(body, fill)
            // 只描两条斜边：底边是纸签自己的上沿，描了会多一道横线
            val sides = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
            }
            drawPath(sides, edge, style = Stroke(width = 1.dp.toPx()))
        }
    }
}
