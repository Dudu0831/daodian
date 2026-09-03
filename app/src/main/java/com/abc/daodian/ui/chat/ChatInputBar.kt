package com.abc.daodian.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.common.MicIcon
import com.abc.daodian.ui.common.SendIcon
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    enabled: Boolean,
    placeholder: String = "说点什么……"
) {
    val colors = DaodianColors.current
    val canSend = text.isNotBlank() && enabled

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(100.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(100.dp))
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(placeholder, style = DaodianType.body, color = colors.muted)
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                textStyle = DaodianType.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.green),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                singleLine = false,
                maxLines = 4
            )
        }

        Spacer(Modifier.width(2.dp))

        Box(
            Modifier
                .size(36.dp)
                .clickable(enabled = enabled, onClick = onMicClick),
            contentAlignment = Alignment.Center
        ) {
            MicIcon(tint = if (enabled) colors.ink2 else colors.muted)
        }

        Box(
            Modifier
                .size(36.dp)
                .background(if (canSend) colors.green else colors.rule2, CircleShape)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            SendIcon(tint = if (canSend) colors.onGreen else colors.surface)
        }
    }
}
