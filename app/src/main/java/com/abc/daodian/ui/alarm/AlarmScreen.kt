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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/**
 * 半夜会看到的那个屏幕 —— app 兑现价值的一刻。见 DESIGN.md §08 界面、视觉稿 Alarm / AlarmDark 两块画板。
 *
 * 荣耀会把通知重要性静默降级（横幅可能不弹），这一屏是主要的送达手段之一，不是锦上添花。
 * 深色那版是按半夜三点看的场景做的：降低对比、不用纯白 —— 所以 AlarmActivity 目前钉死深色，
 * 想让它跟随系统主题的话，改 AlarmActivity 里那行 `darkTheme = true` 就行，这一屏两套色都画得出来。
 */
@Composable
fun AlarmScreen(
    title: String,
    dateLabel: String,
    clockLabel: String,
    onDone: () -> Unit,
    onSnooze: () -> Unit
) {
    val colors = DaodianColors.current

    Column(Modifier.fillMaxSize().background(colors.paper)) {

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 34.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(dateLabel, style = DaodianType.alarmDate, color = colors.muted)
            Spacer(Modifier.height(4.dp))
            Text(clockLabel, style = DaodianType.alarmClock, color = colors.ink.copy(alpha = 0.92f))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Hairline()
            Spacer(Modifier.height(34.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(5.dp).background(colors.accent, CircleShape))
                Text("到点了", style = DaodianType.alarmTag, color = colors.muted)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                title,
                style = DaodianType.alarmTitle,
                color = colors.ink,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(34.dp))
            Hairline()
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(colors.solid, RoundedCornerShape(34.dp))
                    .clickable(onClick = onDone),
                contentAlignment = Alignment.Center
            ) {
                Text("完成", style = DaodianType.button, color = colors.onSolid)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .border(1.dp, colors.rule2, RoundedCornerShape(34.dp))
                    .clickable(onClick = onSnooze),
                contentAlignment = Alignment.Center
            ) {
                Text("稍后 10 分钟", style = DaodianType.button, color = colors.ink2)
            }
        }
    }
}

/** 标题上下那两道短横 —— 把「到点了」这件事框住 */
@Composable
private fun Hairline() {
    val colors = DaodianColors.current
    Box(Modifier.width(26.dp).height(1.dp).background(colors.rule2))
}
