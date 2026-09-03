package com.abc.daodian.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.CheckIcon
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.common.PlusIcon
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.common.TrashIcon
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import java.time.Instant
import java.time.ZonedDateTime

/** 提醒列表。见 DESIGN.md §04 */
@Composable
fun ReminderListScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit
) {
    val colors = DaodianColors.current
    val reminders by vm.reminders.collectAsState()
    val groups = remember(reminders) { group(reminders) }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = "提醒", onBack = onBack) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(colors.green, CircleShape)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) { PlusIcon(tint = colors.onGreen) }
        }

        if (reminders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有提醒 —— 去对话页说一句，或者点右上角手动加一条。",
                    style = DaodianType.body, color = colors.muted
                )
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            groups.forEach { (label, items) ->
                item(key = "h-$label") {
                    Text(
                        label, style = DaodianType.monoSmall, color = colors.muted,
                        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
                    )
                }
                items(items, key = { it.id }) { r ->
                    ReminderRow(
                        r = r,
                        armed = if (r.status == ReminderStatus.SCHEDULED) vm.isArmed(r.id) else true,
                        onDone = { vm.markDone(r) },
                        onDelete = { vm.delete(r) },
                        onClick = { onEdit(r.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun ReminderRow(
    r: Reminder,
    armed: Boolean,
    onDone: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val colors = DaodianColors.current
    val rruleText = remember(r.rrule) { Format.humanRrule(r.rrule) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(4.dp))
            .border(1.dp, if (!armed) colors.red else colors.rule, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = DaodianType.bodySmall.copy(fontSize = 16.sp), color = colors.ink)
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Format.humanDateTime(r.nextTriggerAt), style = DaodianType.caption, color = colors.ink2)
                    rruleText?.let {
                        Text(
                            it, style = DaodianType.monoSmall, color = colors.greenInk,
                            modifier = Modifier.background(colors.greenSoft, RoundedCornerShape(100.dp))
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                        )
                    }
                }
                if (!armed) {
                    Spacer(Modifier.height(6.dp))
                    Text("⚠ 没排上 —— 这条到点不会响", style = DaodianType.caption, color = colors.red)
                }
                if (r.status != ReminderStatus.SCHEDULED) {
                    Spacer(Modifier.height(4.dp))
                    Text(r.status.name, style = DaodianType.caption, color = colors.muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (r.status == ReminderStatus.SCHEDULED) {
                    Box(Modifier.size(30.dp).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
                        CheckIcon(size = 15.dp, tint = colors.green)
                    }
                }
                Box(Modifier.size(30.dp).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                    TrashIcon(size = 15.dp, tint = colors.muted)
                }
            }
        }
    }
}

private fun group(list: List<Reminder>): List<Pair<String, List<Reminder>>> {
    val now = ZonedDateTime.now()
    val active = list.filter { it.status == ReminderStatus.SCHEDULED }.sortedBy { it.nextTriggerAt }
    val done = list.filter { it.status != ReminderStatus.SCHEDULED }.sortedByDescending { it.updatedAt }

    val todayEnd = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()
    val tomorrowEnd = now.toLocalDate().plusDays(2).atStartOfDay(now.zone).toInstant()
    val weekEnd = now.toLocalDate().plusDays(7).atStartOfDay(now.zone).toInstant()

    val today = mutableListOf<Reminder>()
    val tomorrow = mutableListOf<Reminder>()
    val week = mutableListOf<Reminder>()
    val later = mutableListOf<Reminder>()

    active.forEach { r ->
        val t = Instant.ofEpochMilli(r.nextTriggerAt)
        when {
            t.isBefore(todayEnd) -> today.add(r)
            t.isBefore(tomorrowEnd) -> tomorrow.add(r)
            t.isBefore(weekEnd) -> week.add(r)
            else -> later.add(r)
        }
    }

    return buildList {
        if (today.isNotEmpty()) add("今天" to today)
        if (tomorrow.isNotEmpty()) add("明天" to tomorrow)
        if (week.isNotEmpty()) add("本周" to week)
        if (later.isNotEmpty()) add("以后" to later)
        if (done.isNotEmpty()) add("已完成" to done)
    }
}
