package com.abc.daodian.agent.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abc.daodian.agent.feature.PendingTrigger
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

/**
 * 问过你、还等着你点的那一轮（[PendingTrigger]，比如通知弹了、还没对的每晚对账）：
 * 虚线版的 [TriggerDivider]，底下一句话、两个按钮。点主按钮它收走、那一轮开始，
 * 原地换成那条实线分隔线。设计稿：<https://claude.ai/artifact/Nx2XGVSdn5UJZhssSfYwgU>（方向 B）
 */
@Composable
fun PendingTriggerBlock(pending: PendingTrigger, onGo: () -> Unit, onDismiss: () -> Unit) {
    val colors = DaodianColors.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashedRule(colors.rule2, Modifier.weight(1f))
            Text(Format.clock(pending.at) + " · " + pending.label, style = DaodianType.speakerTag, color = colors.hint)
            DashedRule(colors.rule2, Modifier.weight(1f))
        }
        Text(pending.text, style = DaodianType.bodySmall, color = colors.ink2, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shape = RoundedCornerShape(22.dp)
            Box(
                Modifier
                    .height(44.dp)
                    .clip(shape)
                    .background(colors.solid)
                    .clickable(role = Role.Button, onClick = onGo)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(pending.action, style = DaodianType.body, color = colors.onSolid)
            }
            Box(
                Modifier
                    .height(44.dp)
                    .clip(shape)
                    .border(1.dp, colors.rule2, shape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("今天算了", style = DaodianType.body, color = colors.ink2)
            }
        }
    }
}

@Composable
private fun DashedRule(color: Color, modifier: Modifier) {
    Canvas(modifier.height(1.dp)) {
        drawLine(
            color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        )
    }
}
