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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.ui.MicIcon
import com.abc.daodian.shared.ui.SendIcon
import com.abc.daodian.shared.ui.StopIcon
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion

/**
 * 底部那根横条。解析中整条压暗，发送键淡成空心圈里一个墨块 —— 那不只是禁用态，是「停」，
 * 按下去掐断当前这条流（见 DESIGN.md §6.2）。字在一路往外冒的时候，
 * 用户必须能喊停，否则只能干等。
 *
 * 麦克风在听的时候（[listening]）变成墨色实心圆，底下一圈淡墨跟着音量 [level] 胀缩 ——
 * 和桌面速记那枚墨印同一个样子。再点一下＝说完了。
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    enabled: Boolean,
    listening: Boolean = false,
    level: Float = 0f,
    onStop: (() -> Unit)? = null,
    placeholder: String = "说一句话……",
    /**
     * 点了问卡上某一题的「其他…」：句首垫上「¥219.00 是」，描一圈朱砂 ——
     * 这时候打的字只算那一题的答案。见 DESIGN.md §6.6
     */
    scope: String? = null
) {
    val colors = DaodianColors.current
    val canSend = text.isNotBlank() && enabled
    val shape = RoundedCornerShape(26.dp)
    val dim by animateFloatAsState(if (enabled) 1f else 0.55f, tween(Motion.SHORT), label = "inputDim")
    val edge by animateColorAsState(if (scope != null) colors.accent else colors.rule, tween(Motion.SHORT), label = "inputEdge")

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, edge, shape)
            .padding(start = 20.dp, end = 11.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = scope != null,
            enter = expandHorizontally(Motion.settle(Motion.SHORT)) + fadeIn(Motion.settle(Motion.SHORT)),
            exit = shrinkHorizontally(Motion.flow(Motion.SHORT)) + fadeOut(Motion.exit())
        ) {
            // 退场那几帧 scope 已经是 null 了，用最后一次的字画完
            val last = remember { arrayOfNulls<String>(1) }
            scope?.let { last[0] = it }
            Text(last[0].orEmpty(), style = DaodianType.body, color = colors.muted, modifier = Modifier.padding(end = 4.dp))
        }
        Box(Modifier.weight(1f).padding(end = 8.dp).graphicsLayer { alpha = dim }) {
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

        val swell by animateFloatAsState(if (listening) level else 0f, Motion.flow(Motion.CHAR), label = "micSwell")
        val micFill by animateColorAsState(
            if (listening) colors.solid else colors.solid.copy(alpha = 0f), tween(Motion.SHORT), label = "micFill"
        )
        val micTint by animateColorAsState(if (listening) colors.onSolid else colors.ink2, tween(Motion.SHORT), label = "micTint")
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { alpha = dim }
                .clickable(enabled = enabled, onClick = onMicClick),
            contentAlignment = Alignment.Center
        ) {
            if (listening) {
                Box(
                    Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            val s = 1f + swell * 0.45f
                            scaleX = s
                            scaleY = s
                        }
                        .background(colors.ink.copy(alpha = 0.1f), CircleShape)
                )
            }
            Box(Modifier.size(34.dp).background(micFill, CircleShape))
            MicIcon(tint = micTint)
        }

        // 稿子里空输入框的发送键也是实心带箭头 —— 空心圈只属于「解析中」那一档。
        // 没字时按钮还在，只是按不动：按钮凭空消失比按了没反应更让人发懵。
        // 压暗只加在文字和麦克风上，不加在整条 Row 上 —— alpha 会 clamp 到 1，
        // 套在父层的话「停」再怎么提也提不回来，看着就像个禁用的按钮。
        val stopping = !enabled && onStop != null
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

private enum class SendKey { Send, Stop, None }
