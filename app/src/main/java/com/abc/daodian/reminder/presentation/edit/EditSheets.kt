package com.abc.daodian.reminder.presentation.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/*
 * 编辑页的两张底纸：「几点」滚轮、「重复」选项。
 * 不用 Material 的 TimePicker —— 那个表盘是 Material 的长相，跟墨宋放一起像借来的。
 */

private val CommonTimes = listOf(
    LocalTime.of(9, 0), LocalTime.of(12, 0), LocalTime.of(18, 0), LocalTime.of(20, 0), LocalTime.of(21, 30)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = DaodianColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        scrimColor = colors.ink.copy(alpha = 0.32f),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 18.dp).size(36.dp, 4.dp)
                    .background(colors.rule, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 20.dp)) { content() }
    }
}

/** 「几点」：时、分两只滚轮 + 一排常用钟点 + 「就这个点」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeSheet(initial: LocalTime, dayLabel: String, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val colors = DaodianColors.current
    var hour by remember { mutableStateOf(initial.hour) }
    var minute by remember { mutableStateOf(initial.minute) }

    PaperSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("几点", style = DaodianType.sectionLabel, color = colors.muted)
                Text(dayLabel, style = DaodianType.caption, color = colors.muted)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // 正中那一格上下两道线，标出「选中的是这个」
                Column(Modifier.fillMaxWidth().height(WheelItem * 5)) {
                    Spacer(Modifier.height(WheelItem * 2))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
                    Spacer(Modifier.height(WheelItem - 2.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wheel(count = 24, value = hour, onPick = { hour = it })
                    Text(":", style = DaodianType.wheel, color = colors.ink, modifier = Modifier.padding(horizontal = 6.dp))
                    Wheel(count = 60, value = minute, onPick = { minute = it })
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CommonTimes.forEach { t ->
                    Chip(t.toString(), selected = t.hour == hour && t.minute == minute) {
                        hour = t.hour; minute = t.minute
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            SolidButton("就这个点") { onPick(LocalTime.of(hour, minute)) }
        }
    }
}

private val WheelItem = 44.dp

/**
 * 一只滚轮：五格高，正中那格就是选中值，松手吸附到格子上。
 * 外面改了 [value]（点了常用钟点）就滚过去；自己滚到哪就报哪，两边不打架。
 */
@Composable
private fun Wheel(count: Int, value: Int, onPick: (Int) -> Unit) {
    val colors = DaodianColors.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value)
    val itemPx = with(LocalDensity.current) { WheelItem.toPx() }
    val centered by remember {
        derivedStateOf {
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0)
                .coerceIn(0, count - 1)
        }
    }
    // 只在停稳时报数：滚到一半就报，外面改 value 会把正在进行的 animateScrollToItem 打断在半路
    LaunchedEffect(centered, state.isScrollInProgress) { if (!state.isScrollInProgress) onPick(centered) }
    LaunchedEffect(value) { if (value != centered) state.animateScrollToItem(value) }

    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = WheelItem * 2),
        modifier = Modifier.width(76.dp).height(WheelItem * 5),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(count) { i ->
            val d = abs(i - centered)
            Box(Modifier.height(WheelItem).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "%02d".format(i),
                    style = when (d) {
                        0 -> DaodianType.wheel
                        1 -> DaodianType.axisTimeNext
                        else -> DaodianType.axisTime
                    },
                    color = when (d) {
                        0 -> colors.ink
                        1 -> colors.muted
                        else -> colors.rule2
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 一个重复选项：名字 + 可选的一句说明 */
data class RepeatOption(val choice: RepeatChoice, val label: String, val note: String?)

/** 「重复」：把规则写成具体的「每周六」「每月 19 号」，不让人猜按的是哪天 */
@Composable
fun RepeatSheet(options: List<RepeatOption>, current: RepeatChoice, onPick: (RepeatChoice) -> Unit, onDismiss: () -> Unit) {
    val colors = DaodianColors.current
    PaperSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "重复", style = DaodianType.sectionLabel, color = colors.muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            options.forEachIndexed { i, o ->
                if (i > 0) GroupRule()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(o.choice) }
                        .padding(start = 18.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.label, style = DaodianType.body, color = colors.ink)
                        if (o.note != null) {
                            Text(o.note, style = DaodianType.settingNote, color = colors.muted)
                        }
                    }
                    if (o.choice == current) CheckIcon(size = 13.dp, tint = colors.accent)
                }
            }
            Text(
                "更复杂的规则（每两周、工作日）直接跟到点说一句。",
                style = DaodianType.settingNote, color = colors.muted,
                modifier = Modifier.padding(start = 4.dp, top = 14.dp)
            )
        }
    }
}

/** 「提前提醒」的一种排法：名字 + 照那天算出来的几天 */
private class NudgeOption(val label: String, val days: List<LocalDate>)

/** 这页排得出来的几种。只留今天以后、那天以前的；一天都排不出来的（日子太近）不列 */
private fun nudgeOptions(today: LocalDate, due: LocalDate): List<NudgeOption> {
    fun keep(days: List<LocalDate>) = days.filter { it.isAfter(today) && it.isBefore(due) }.sorted()
    return listOf(
        NudgeOption("提前一天", keep(listOf(due.minusDays(1)))),
        NudgeOption("提前三天", keep(listOf(due.minusDays(3)))),
        NudgeOption("提前一周", keep(listOf(due.minusDays(7)))),
        NudgeOption("最后一周每天", keep((1L..6L).map { due.minusDays(it) })),
        NudgeOption("每周一次", keep(generateSequence(1L) { it + 1 }.map { due.minusWeeks(it) }.takeWhile { it.isAfter(today) }.toList())),
        NudgeOption("每月一次", keep(generateSequence(1L) { it + 1 }.map { due.minusMonths(it) }.takeWhile { it.isAfter(today) }.toList()))
    ).filter { it.days.isNotEmpty() }
}

/**
 * 「提前提醒」：那天之前还要哪几天提醒（DESIGN §4.4）。几种排法可以叠着勾，勾一下当场生效，不用点确定。
 * 模型排的、这里画不出来的那几天（「每十天一次」）原样留着，只有点「不提前」才清掉。
 */
@Composable
fun NudgeSheet(
    today: LocalDate, due: LocalDate, current: List<LocalDate>,
    onChange: (List<LocalDate>) -> Unit, onDismiss: () -> Unit
) {
    val colors = DaodianColors.current
    val options = remember(today, due) { nudgeOptions(today, due) }
    PaperSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "提前提醒", style = DaodianType.sectionLabel, color = colors.muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            @Composable
            fun Line(label: String, value: String?, on: Boolean, onClick: () -> Unit) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .padding(start = 18.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = DaodianType.body, color = colors.ink, modifier = Modifier.weight(1f))
                    if (value != null) Text(value, style = DaodianType.settingNote, color = colors.muted)
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
                        if (on) CheckIcon(size = 13.dp, tint = colors.accent)
                    }
                }
            }
            Line("不提前", null, on = current.isEmpty()) { onChange(emptyList()) }
            options.forEach { o ->
                GroupRule()
                val on = current.containsAll(o.days)
                Line(o.label, "${o.days.size} 次", on) {
                    onChange(if (on) current - o.days.toSet() else (current + o.days).distinct().sorted())
                }
            }
            Text(
                if (current.isEmpty()) "那天晚上照常提醒一次。" else "一共 ${current.size} 次，那天晚上另外再提醒一次。",
                style = DaodianType.settingNote, color = colors.muted,
                modifier = Modifier.padding(start = 4.dp, top = 14.dp)
            )
        }
    }
}

/** 描边小圆角钮；选中了是墨色实心（§8.1 第 1 条） */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(colors.solid, shape) else Modifier.border(1.dp, colors.rule2, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, style = DaodianType.bodySmall, color = if (selected) colors.onSolid else colors.ink2)
    }
}

/** 整宽的墨色大按钮，圆角 34dp */
@Composable
fun SolidButton(label: String, onClick: () -> Unit) {
    val colors = DaodianColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(34.dp))
            .background(colors.solid)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = DaodianType.button, color = colors.onSolid)
    }
}
