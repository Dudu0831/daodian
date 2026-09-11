package com.abc.daodian.ui.chat

import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.common.MicIcon
import com.abc.daodian.ui.common.SendIcon
import com.abc.daodian.ui.common.StopIcon
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import com.abc.daodian.ui.theme.Motion

/**
 * 底部那根横条。解析中整条压暗，发送键淡成空心圈里一个墨块 —— 那不只是禁用态，是「停」，
 * 按下去掐断当前这条流（见 DESIGN.md §6.7）。字在一路往外冒的时候，
 * 用户必须能喊停，否则只能干等。
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    enabled: Boolean,
    onStop: (() -> Unit)? = null,
    placeholder: String = "说一句话……"
) {
    val colors = DaodianColors.current
    val canSend = text.isNotBlank() && enabled
    val shape = RoundedCornerShape(26.dp)
    val dim by animateFloatAsState(if (enabled) 1f else 0.55f, tween(Motion.SHORT), label = "inputDim")

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .padding(start = 20.dp, end = 11.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer { alpha = dim }
                .clickable(enabled = enabled, onClick = onMicClick),
            contentAlignment = Alignment.Center
        ) {
            MicIcon(tint = colors.ink2)
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
