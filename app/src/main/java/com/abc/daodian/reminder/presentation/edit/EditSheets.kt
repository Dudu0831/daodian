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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.domain.PlanValidator
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/*
 * 编辑页的几张底纸：几点、重复、提前提醒、没点完成。
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

/**
 * 「几点」（设计稿甲，§4.6）：上面一排挑好的钟点，朱砂框那颗是滚轮正在改的，点别的那颗就改它，× 去掉；
 * 「＋ 再加一个」多一颗跟着滚。「不定」清空、滚轮变淡；不定时滚一下滚轮，就是定成那一个钟点。
 * 只改一个钟点和以前一样：滚一下点「好」。最多 [MaxTimes] 个。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeSheet(initial: List<LocalTime>, dayLabel: String, onPick: (List<LocalTime>) -> Unit, onDismiss: () -> Unit) {
    val colors = DaodianColors.current
    var times by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf(if (initial.isEmpty()) -1 else 0) }
    val first = initial.firstOrNull() ?: LocalTime.of(20, 0)
    var hour by remember { mutableStateOf(first.hour) }
    var minute by remember { mutableStateOf(first.minute) }

    fun focus(i: Int) {
        editing = i
        times.getOrNull(i)?.let { hour = it.hour; minute = it.minute }
    }
    // 滚轮停稳时报数：改的是正在改的那颗；不定时滚到别的钟点就定成它
    fun wheelMoved(t: LocalTime) {
        if (editing >= 0) times = times.toMutableList().also { it[editing] = t }
        else { times = listOf(t); editing = 0 }
    }

    PaperSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("几点", style = DaodianType.sectionLabel, color = colors.muted)
                Text(dayLabel + if (times.size > 1) " · ${times.size} 个" else "", style = DaodianType.caption, color = colors.muted)
            }
            if (times.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    times.forEachIndexed { i, t ->
                        PickedChip(
                            t.toString(), editing = i == editing,
                            onClick = { focus(i) },
                            onRemove = if (times.size > 1) ({
                                times = times.filterIndexed { j, _ -> j != i }
                                focus(if (editing >= i) (editing - 1).coerceAtLeast(0) else editing)
                            }) else null
                        )
                    }
                    if (times.size < MaxTimes) {
                        GhostChip("＋ 再加一个") {
                            val last = times.lastOrNull() ?: LocalTime.of(11, 0)
                            times = times + last.plusHours(1)
                            focus(times.size - 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().alpha(if (editing < 0) 0.3f else 1f), contentAlignment = Alignment.Center) {
                // 正中那一格上下两道线，标出「选中的是这个」
                Column(Modifier.fillMaxWidth().height(WheelItem * 5)) {
                    Spacer(Modifier.height(WheelItem * 2))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
                    Spacer(Modifier.height(WheelItem - 2.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wheel(count = 24, value = hour, onPick = { if (it != hour) { hour = it; wheelMoved(LocalTime.of(hour, minute)) } })
                    Text(":", style = DaodianType.wheel, color = colors.ink, modifier = Modifier.padding(horizontal = 6.dp))
                    Wheel(count = 60, value = minute, onPick = { if (it != minute) { minute = it; wheelMoved(LocalTime.of(hour, minute)) } })
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("不定", selected = times.isEmpty()) { times = emptyList(); editing = -1 }
                CommonTimes.forEach { t ->
                    Chip(t.toString(), selected = editing >= 0 && t.hour == hour && t.minute == minute) {
                        hour = t.hour; minute = t.minute
                        if (editing >= 0) times = times.toMutableList().also { it[editing] = t }
                        else { times = listOf(t); editing = 0 }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            SolidButton(if (times.isEmpty()) "不定钟点" else "好") { onPick(times.distinct().sorted()) }
        }
    }
}

/** 一天最多几个钟点，和闸门一样 */
private const val MaxTimes = PlanValidator.MAX_TIMES

/** 挑好的一颗钟点：正在改的描朱砂框；[onRemove] 不是 null 才有 × */
@Composable
private fun PickedChip(label: String, editing: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .clip(shape)
            .border(if (editing) 1.5.dp else 1.dp, if (editing) colors.accent else colors.rule2, shape)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onRemove != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, style = DaodianType.bodySmall, color = if (editing) colors.accent else colors.ink2,
            modifier = Modifier.padding(vertical = 9.dp)
        )
        if (onRemove != null) {
            Text(
                "×", style = DaodianType.bodySmall, color = colors.muted,
                modifier = Modifier.clickable(onClick = onRemove).padding(horizontal = 8.dp, vertical = 9.dp)
            )
        }
    }
}

/** 虚线的「＋ 再加一个」 */
@Composable
private fun GhostChip(label: String, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .clip(shape)
            .dashedBorder(colors.rule2, 22.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, style = DaodianType.bodySmall, color = colors.hint)
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier =
    drawBehind {
        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        )
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

/** 「提前提醒」的一种排法：名字 + 提前几天 */
private class NudgeOption(val label: String, val days: List<Int>)

private val NudgeOptions = listOf(
    NudgeOption("提前一天", listOf(1)),
    NudgeOption("提前三天", listOf(3)),
    NudgeOption("提前一周", listOf(7)),
    NudgeOption("提前一个月", listOf(30)),
    NudgeOption("最后一周每天", (1..6).toList())
)

/**
 * 「提前提醒」（§4.6）：那天之前提前几天，几种可以叠着勾，勾一下当场生效。
 * 重复的只列比两次间隔（[gap]）短的；一次性的只列还排得上的（那几天里至少一天在今天以后）。
 * 模型排的、这里没有的天数（「提前 10 天」）原样留着，只有点「不提前」才清掉。
 */
@Composable
fun NudgeSheet(
    day: LocalDate, today: LocalDate, gap: Long?, repeating: Boolean, checkTime: LocalTime,
    current: List<Int>, onChange: (List<Int>) -> Unit, onDismiss: () -> Unit
) {
    val colors = DaodianColors.current
    val options = remember(day, today, gap) {
        NudgeOptions.filter { o ->
            (gap == null || o.days.max() < gap) && (repeating || o.days.any { day.minusDays(it.toLong()).isAfter(today) })
        }
    }
    PaperSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            SheetHead("提前提醒", "${day.monthValue}月${day.dayOfMonth}日")
            SheetLine("不提前", on = current.isEmpty()) { onChange(emptyList()) }
            options.forEach { o ->
                GroupRule()
                val on = current.containsAll(o.days)
                val value = if (o.days.size == 1) day.minusDays(o.days[0].toLong()).let { "${it.monthValue}月${it.dayOfMonth}日" }
                else "${o.days.size} 次"
                SheetLine(o.label, value = value, on = on) {
                    onChange(if (on) current - o.days.toSet() else (current + o.days).distinct().sortedDescending())
                }
            }
            Text(
                "都在那天之前的 ${checkTime.toString().take(5)} 发一条通知。",
                style = DaodianType.settingNote, color = colors.muted,
                modifier = Modifier.padding(start = 4.dp, top = 14.dp)
            )
        }
    }
}

/** 「没点完成」（§4.6）：三选一，选了就收起。每天都有的不能每晚再提（[dailyAllowed] = false 时那行灰着） */
@Composable
fun NagSheet(current: Nag, dailyAllowed: Boolean, checkTime: LocalTime, onPick: (Nag) -> Unit, onDismiss: () -> Unit) {
    PaperSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            SheetHead("没点完成", null)
            SheetLine("不催", note = "响过就算", on = current == Nag.NONE) { onPick(Nag.NONE) }
            GroupRule()
            SheetLine("过一会儿再提", note = "30 分钟一次，最多 3 次", on = current == Nag.SOON) { onPick(Nag.SOON) }
            GroupRule()
            SheetLine(
                "每晚再提",
                note = if (dailyAllowed) "每晚 ${checkTime.toString().take(5)} 一次，直到点完成" else "每天本来就会再响",
                on = current == Nag.DAILY, enabled = dailyAllowed
            ) { onPick(Nag.DAILY) }
        }
    }
}

@Composable
private fun SheetHead(label: String, right: String?) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = DaodianType.sectionLabel, color = colors.muted)
        if (right != null) Text(right, style = DaodianType.caption, color = colors.muted)
    }
}

/** 底纸里的一行：名字（+ 一行小字）、右边的值、选中打朱砂勾 */
@Composable
private fun SheetLine(
    label: String, note: String? = null, value: String? = null, on: Boolean, enabled: Boolean = true, onClick: () -> Unit
) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = DaodianType.body, color = if (enabled) colors.ink else colors.hint)
            if (note != null) Text(note, style = DaodianType.settingNote, color = if (enabled) colors.muted else colors.hint)
        }
        if (value != null) Text(value, style = DaodianType.settingNote, color = colors.muted)
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            if (on) CheckIcon(size = 13.dp, tint = colors.accent)
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
