package com.abc.daodian.assistant.conversation.turn

import com.abc.daodian.assistant.conversation.ConversationItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

/** app 自己发起的一轮（每晚对账）：不是用户说的话，画成一条分隔线 */
@Composable
fun TriggerDivider(msg: ConversationItem.UserText) {
    val colors = DaodianColors.current
    // 开场白都是「每晚对账：……」这样，冒号前一段就是标签
    val label = msg.text.substringBefore('：').take(12)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.rule)
        Text(
            (if (msg.sentAt > 0) Format.clock(msg.sentAt) + " · " else "") + label,
            style = DaodianType.speakerTag, color = colors.hint
        )
        HorizontalDivider(Modifier.weight(1f), color = colors.rule)
    }
}
