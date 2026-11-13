package com.abc.daodian.assistant.conversation.turn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

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
