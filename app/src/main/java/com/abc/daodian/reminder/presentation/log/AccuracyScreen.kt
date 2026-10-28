package com.abc.daodian.reminder.presentation.log

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.data.FireLog
import com.abc.daodian.reminder.data.FireSource
import com.abc.daodian.reminder.presentation.ReminderViewModel
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 响得准不准：最近 [Accuracy.WINDOW] 次投递的统计（一句结论、一排漂移、三个数）。DESIGN.md §9.3 的验收靠它看个大概，
 * 每一次的明细只在导出诊断里（`ReminderDiagnostics`），这里不列。设计稿：https://claude.ai/artifact/VhupokZXiZjFpi4iijS72w
 */
@Composable
fun AccuracyScreen(vm: ReminderViewModel, onBack: () -> Unit) {
    val colors = DaodianColors.current
    val logs by vm.logs.collectAsState()
    val a = Accuracy.of(logs)

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "响得准不准", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.verdict.text, style = DaodianType.verdict, color = if (a.verdict.ok) colors.ink else colors.red)
                if (a.verdict == Accuracy.Verdict.GOOD) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(26.dp).border(1.dp, colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                        CheckIcon(size = 12.dp, tint = colors.accent)
                    }
                }
            }
            if (a.count == 0) return@Column

            GroupLabel("最近 ${a.count} 次")
            PaperGroup {
                DriftBars(a.recent)
                GroupRule()
                StatRow("中位漂移", drift(a.medianMillis))
                GroupRule()
                StatRow("最慢一次", drift(a.maxMillis))
                GroupRule()
                StatRow("走了兜底补发", "${a.fallbacks}", bad = a.fallbacks > 0)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 一次一根，从旧到新。走了兜底补发的红，超过 [Accuracy.SLOW_MILLIS] 的墨色，其余淡墨；高度按这几次里最大的那次比 */
@Composable
private fun DriftBars(recent: List<FireLog>) {
    val colors = DaodianColors.current
    val max = recent.maxOf { abs(it.driftMillis) }.coerceAtLeast(1_000)
    val zone = ZoneId.systemDefault()
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 18.dp, bottom = 12.dp)) {
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val n = recent.size
            val gap = 3.dp.toPx()
            val w = ((size.width - gap * (n - 1)) / n).coerceAtMost(10.dp.toPx())
            recent.forEachIndexed { i, log ->
                val h = (abs(log.driftMillis).toFloat() / max * size.height).coerceAtLeast(3.dp.toPx())
                val color: Color = when {
                    log.source != FireSource.ALARM -> colors.red
                    abs(log.driftMillis) > Accuracy.SLOW_MILLIS -> colors.ink2
                    else -> colors.rule2
                }
                drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(1.dp.toPx()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val first = day(recent.first().firedAt)
            val last = day(recent.last().firedAt)
            Text(dayLabel(first), style = DaodianType.caption, color = colors.muted)
            if (last != first) Text(dayLabel(last), style = DaodianType.caption, color = colors.muted)
        }
    }
}

private fun dayLabel(d: LocalDate): String =
    if (d == LocalDate.now()) "今天" else "%02d-%02d".format(d.monthValue, d.dayOfMonth)

@Composable
private fun StatRow(title: String, value: String, bad: Boolean = false) {
    val colors = DaodianColors.current
    SettingRow(title = title, titleColor = if (bad) colors.red else colors.ink) {
        Text(value, style = DaodianType.settingValue, color = if (bad) colors.red else colors.ink)
    }
}

/** 最近几次投递的统计。纯函数，测试直接调 */
internal class Accuracy private constructor(
    /** 从旧到新 */
    val recent: List<FireLog>
) {
    val count = recent.size
    private val sorted = recent.map { it.driftMillis }.sorted()
    val medianMillis = if (sorted.isEmpty()) 0L else sorted[sorted.size / 2]
    val maxMillis = sorted.maxByOrNull { abs(it) } ?: 0L
    val fallbacks = recent.count { it.source != FireSource.ALARM }

    val verdict = when {
        count == 0 -> Verdict.NONE
        fallbacks > 0 -> Verdict.SQUEEZED
        abs(maxMillis) > SLOW_MILLIS -> Verdict.SLOW
        else -> Verdict.GOOD
    }

    enum class Verdict(val text: String, val ok: Boolean) {
        NONE("还没响过", true),
        GOOD("很准", true),
        SLOW("有几次慢了", true),
        /** 走了兜底补发：主闹钟在被掐 */
        SQUEEZED("主闹钟在被掐", false)
    }

    companion object {
        const val WINDOW = 30

        /** 慢过这么多算慢（和 DESIGN.md §9.3 的验收线一样） */
        const val SLOW_MILLIS = 30_000L

        /** [logs] 是库里新的在前（`observeRecent`） */
        fun of(logs: List<FireLog>) = Accuracy(logs.take(WINDOW).reversed())
    }
}

/** 漂移：十秒以内留一位小数（看得出是 0.2s 还是 0.9s），一分钟以内取整秒，再大就写人话时长 */
internal fun drift(ms: Long): String {
    val sign = if (ms >= 0) "+" else "−"
    val a = abs(ms)
    return when {
        a < 10_000 -> "$sign${"%.1f".format(a / 1000.0)}s"
        a < 60_000 -> "$sign${a / 1000}s"
        else -> sign + Format.span(a)
    }
}
