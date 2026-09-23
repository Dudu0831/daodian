package com.abc.daodian.reminder.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.DrawerPaper
import com.abc.daodian.shared.ui.DrawerPaperHead
import com.abc.daodian.shared.ui.activityViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 抽屉里提醒那张纸：下一条的大字时刻 + 再下一条 */
@Composable
fun ReminderDrawerCard(onOpen: () -> Unit) {
    val vm = activityViewModel<ReminderViewModel>()
    val reminders by vm.reminders.collectAsState()
    val colors = DaodianColors.current
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val upcoming = reminders.filter { it.status == ReminderStatus.SCHEDULED && it.nextTriggerAt >= now }.sortedBy { it.nextTriggerAt }
    val todayLeft = upcoming.count { Instant.ofEpochMilli(it.nextTriggerAt).atZone(zone).toLocalDate() == today }

    DrawerPaper(onOpen) {
        DrawerPaperHead("提醒", if (todayLeft > 0) "今天还有 $todayLeft 条 ›" else "全部 ›")
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
