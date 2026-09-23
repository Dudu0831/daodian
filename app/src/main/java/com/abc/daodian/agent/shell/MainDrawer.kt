package com.abc.daodian.agent.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.ledger.presentation.CategorySlice
import com.abc.daodian.ledger.presentation.LedgerFormat
import com.abc.daodian.ledger.presentation.Overview
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.SettingsIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 左边的抽屉（设计稿方向 B「两张纸」：<https://claude.ai/artifact/PRk3CWeu24V4tKZgxkGLwn>）。
 *
 * 两张纸不点进去也看得到要紧的：下一条提醒几点、这个月花了多少。点纸进各自的页；
 * 设置压在最底下，右边一句体检结论。以后有新的一块（比如别的统计）就再加一张纸。
 */
@Composable
fun MainDrawer(
    reminders: List<Reminder>,
    month: Overview?,
    checkTime: String,
    healthMissing: Int,
    onClose: () -> Unit,
    onOpenList: () -> Unit,
    onOpenLedger: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = DaodianColors.current
    Column(
        Modifier
            .width(318.dp)
            .fillMaxHeight()
            .background(colors.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("到点", style = DaodianType.screenTitle.copy(letterSpacing = 0.12.sp), color = colors.ink)
            Text(
                "回到对话", style = DaodianType.caption, color = colors.muted,
                modifier = Modifier.clickable(onClick = onClose).padding(vertical = 12.dp, horizontal = 4.dp)
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            ReminderPaper(reminders, onOpenList)
            LedgerPaper(month, checkTime, onOpenLedger)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 10.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsIcon(tint = colors.ink2)
            Text("设置", style = DaodianType.body, color = colors.ink2, modifier = Modifier.weight(1f))
            Text(
                if (healthMissing == 0) "一切正常" else "还差 $healthMissing 项",
                style = DaodianType.caption,
                color = if (healthMissing == 0) colors.muted else colors.red
            )
        }
    }
}

@Composable
private fun Paper(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(5.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun PaperHead(label: String, link: String) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DaodianType.sectionLabel, color = colors.muted)
        Text(link, style = DaodianType.caption, color = colors.muted)
    }
}

/** 提醒那张纸：下一条的大字时刻 + 再下一条 */
@Composable
private fun ReminderPaper(reminders: List<Reminder>, onOpen: () -> Unit) {
    val colors = DaodianColors.current
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val upcoming = reminders.filter { it.status == ReminderStatus.SCHEDULED && it.nextTriggerAt >= now }.sortedBy { it.nextTriggerAt }
    val todayLeft = upcoming.count { Instant.ofEpochMilli(it.nextTriggerAt).atZone(zone).toLocalDate() == today }

    Paper(onOpen) {
        PaperHead("提醒", if (todayLeft > 0) "今天还有 $todayLeft 条 ›" else "全部 ›")
        val next = upcoming.firstOrNull()
        if (next == null) {
            Text("接下来没有安排", style = DaodianType.body, color = colors.muted)
        } else {
            val onToday = Instant.ofEpochMilli(next.nextTriggerAt).atZone(zone).toLocalDate() == today
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (next.dueDay != null) "今天之内".takeIf { onToday } ?: Format.humanDateTimeShort(next.nextTriggerAt)
                    else if (onToday) Format.clock(next.nextTriggerAt) else Format.humanDateTimeShort(next.nextTriggerAt),
                    style = DaodianType.greeting.copy(fontSize = if (onToday && next.dueDay == null) 30.sp else 19.sp, lineHeight = 34.sp),
                    color = colors.ink
                )
                Text(
                    next.title, style = DaodianType.body, color = colors.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            upcoming.getOrNull(1)?.let { then ->
                Text(
                    "然后 ${Format.humanDateTimeShort(then.nextTriggerAt)} ${then.title}",
                    style = DaodianType.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 记账那张纸：这个月支出、收入，一根按类别分段的细条，没认出来的几笔 */
@Composable
private fun LedgerPaper(month: Overview?, checkTime: String, onOpen: () -> Unit) {
    val colors = DaodianColors.current
    Paper(onOpen) {
        PaperHead("${month?.period?.kicker ?: "本月"} · 记账", "明细 ›")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("支出", style = DaodianType.caption, color = colors.muted)
                Text(LedgerFormat.yuan(month?.spent ?: 0), style = DaodianType.greeting.copy(fontSize = 27.sp, lineHeight = 32.sp), color = colors.ink)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("收入", style = DaodianType.caption, color = colors.muted)
                Text(LedgerFormat.yuan(month?.income ?: 0), style = DaodianType.cardTitle, color = colors.ink2)
            }
        }
        val slices = month?.spending.orEmpty().filter { it.amount > 0 }
        if (slices.isNotEmpty()) {
            StackedBar(slices)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                slices.take(3).forEach { s ->
                    Text(
                        "${s.name} ${LedgerFormat.money(s.amount).substringBefore('.')}",
                        style = DaodianType.caption,
                        color = if (s.topId == null) colors.muted else colors.ink2, maxLines = 1
                    )
                }
            }
        } else if (month != null) {
            Text("这个月还没有账", style = DaodianType.caption, color = colors.muted)
        }
        if ((month?.pending ?: 0) > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(5.dp).background(colors.accent, CircleShape))
                Text("${month!!.pending} 笔没认出来，$checkTime 问你", style = DaodianType.caption, color = colors.accent)
            }
        }
    }
}

/** 一根细条，按类别分段：前三类墨色深浅，其余并成一段，未归类是虚线框 */
@Composable
private fun StackedBar(slices: List<CategorySlice>) {
    val colors = DaodianColors.current
    val known = slices.filter { it.topId != null }
    val unknown = slices.filter { it.topId == null }.sumOf { it.amount }
    val parts = known.take(3).mapIndexed { i, s -> s.amount to colors.ink.copy(alpha = 1f - i * 0.28f) } +
        listOfNotNull(known.drop(3).sumOf { it.amount }.takeIf { it > 0 }?.let { it to colors.rule2 })
    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (amount, color) ->
            Box(Modifier.weight(amount.toFloat()).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
        }
        if (unknown > 0) {
            Box(
                Modifier.weight(unknown.toFloat()).fillMaxHeight()
                    .border(1.dp, colors.hint, RoundedCornerShape(1.dp))
            )
        }
    }
}
