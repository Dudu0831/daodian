package com.abc.daodian.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.ui.theme.AlarmPalette
import com.abc.daodian.ui.theme.DaodianType

/**
 * 半夜会看到的那个屏幕。固定深色，不跟系统主题走 —— 见 DESIGN.md §06。
 * 荣耀会把通知重要性静默降级（横幅可能不弹），这一屏是主要的送达手段之一，不是锦上添花。
 */
@Composable
fun AlarmScreen(
    title: String,
    nowLabel: String,
    onDone: () -> Unit,
    onSnooze: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(AlarmPalette.bgGlow, AlarmPalette.bg)))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 40.dp, start = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(Modifier.size(9.dp).background(AlarmPalette.accent, CircleShape))
            Text("到点", style = DaodianType.wordmark.copy(fontSize = 14.sp), color = AlarmPalette.muted)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "现在 $nowLabel",
                style = DaodianType.monoSmall.copy(fontSize = 13.sp),
                color = AlarmPalette.muted
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = DaodianType.alarmTitle,
                color = AlarmPalette.ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text("到点了。", fontSize = 14.sp, color = AlarmPalette.muted)
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp).padding(bottom = 54.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AlarmPalette.accent, RoundedCornerShape(100.dp))
                    .clickable(onClick = onDone)
                    .padding(vertical = 17.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("完成", fontSize = 16.sp, color = AlarmPalette.accentInk)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, AlarmPalette.rule, RoundedCornerShape(100.dp))
                    .clickable(onClick = onSnooze)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("稍后 10 分钟", fontSize = 15.sp, color = AlarmPalette.secondaryInk)
            }
        }
    }
}
