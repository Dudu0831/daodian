package com.abc.daodian.assistant.conversation.turn

import com.abc.daodian.assistant.conversation.render.InkText
import com.abc.daodian.assistant.conversation.render.fadeTop
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion

/**
 * 思考过程流着的时候：左边一根细线，字压到最轻的一档。
 * 最多露三行，更早的行在顶上渐隐 —— 思考不该把下面的内容越推越远。
 */
@Composable
internal fun ReasoningStream(text: String) {
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
internal fun ReasoningTrace(text: String, seconds: Int, expanded: Boolean, onToggle: () -> Unit) {
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
