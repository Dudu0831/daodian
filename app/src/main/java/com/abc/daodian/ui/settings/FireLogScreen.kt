package com.abc.daodian.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.data.FireLog
import com.abc.daodian.data.FireSource
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/**
 * 投递日志 —— 把「感觉挺准的」变成可核对的数据。见 DESIGN.md §9.3 的验收标准就是靠这一页看的。
 */
@Composable
fun FireLogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val colors = DaodianColors.current
    val logs by vm.logs.collectAsState()
    val nonAlarm by vm.nonAlarmCount.collectAsState()

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "投递日志", onBack = onBack) {
            TextButton(onClick = { vm.clearLogs() }) { Text("清空", color = colors.muted) }
        }

        val drifts = logs.map { it.driftMillis }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                buildString {
                    append("${logs.size} 条投递记录")
                    if (drifts.isNotEmpty()) {
                        append(" · 最大漂移 ${drifts.max() / 1000}s")
                        append(" · 中位 ${drifts.sorted()[drifts.size / 2] / 1000}s")
                    }
                },
                style = DaodianType.bodySmall, color = colors.ink
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (logs.isEmpty()) "还没有记录"
                else if (nonAlarm == 0) "全部走主闹钟路径 ✓"
                else "⚠ $nonAlarm 条走了兜底补发 —— 主闹钟路径正在被掐，回去检查权限体检和厂商的应用启动管理",
                style = DaodianType.caption,
                color = if (nonAlarm == 0) colors.muted else colors.red
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = colors.rule)

        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)) {
            items(logs, key = { it.id }) { log ->
                LogRow(log)
                HorizontalDivider(color = colors.rule)
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun LogRow(log: FireLog) {
    val colors = DaodianColors.current
    val drift = log.driftMillis
    val bad = drift > 30_000 || log.source != FireSource.ALARM

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(log.title, style = DaodianType.bodySmall, color = colors.ink)
            Text(
                log.source.name,
                fontFamily = DaodianType.basis.fontFamily,
                fontSize = 10.sp,
                color = if (bad) colors.red else colors.muted
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "应响 ${Format.humanDateTime(log.scheduledAt)} → 实响 ${Format.humanDateTime(log.firedAt)} · " +
                "漂移 ${if (drift >= 0) "+" else ""}${drift / 1000}s",
            fontFamily = DaodianType.basis.fontFamily,
            fontSize = 11.sp,
            color = if (bad) colors.red else colors.muted
        )
    }
}
