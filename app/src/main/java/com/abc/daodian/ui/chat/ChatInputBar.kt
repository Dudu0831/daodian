package com.abc.daodian.ui.chat

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.common.MicIcon
import com.abc.daodian.ui.common.SendIcon
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/**
 * 底部那根横条。解析中整条压暗 + 发送键变成空心圈 —— 让「现在轮不到你说话」这件事一眼可见。
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    enabled: Boolean,
    placeholder: String = "说一句话……"
) {
    val colors = DaodianColors.current
    val canSend = text.isNotBlank() && enabled
    val shape = RoundedCornerShape(26.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .padding(start = 20.dp, end = 11.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).padding(end = 8.dp)) {
            if (text.isEmpty()) {
                Text(placeholder, style = DaodianType.body, color = colors.hint)
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
                maxLines = 4
            )
        }

        Box(
            Modifier
                .size(36.dp)
                .clickable(enabled = enabled, onClick = onMicClick),
            contentAlignment = Alignment.Center
        ) {
            MicIcon(tint = colors.ink2)
        }

        // 稿子里空输入框的发送键也是实心带箭头 —— 空心圈只属于「解析中」那一档。
        // 没字时按钮还在，只是按不动：按钮凭空消失比按了没反应更让人发懵。
        Box(
            Modifier
                .size(36.dp)
                .let {
                    if (enabled) it.background(colors.solid, CircleShape)
                    else it.border(1.5.dp, colors.rule2, CircleShape)
                }
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            if (enabled) SendIcon(tint = if (canSend) colors.onSolid else colors.onSolid.copy(alpha = 0.45f))
        }
    }
}
