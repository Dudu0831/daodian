package com.abc.daodian.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import com.abc.daodian.data.dueDate
import com.abc.daodian.data.isAllDay
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.CheckIcon
import com.abc.daodian.ui.common.ChevronRightIcon
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.common.PlusIcon
import com.abc.daodian.ui.common.RepeatIcon
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.common.TrashIcon
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import com.abc.daodian.ui.theme.Motion
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒列表 · 时间轴。见 DESIGN.md §08，设计稿「方向 B」：
 * https://claude.ai/code/artifact/4de04ade-2aa5-4486-b1b7-293ff283f00d
 *
 * 一根竖线从今天早上走到以后，朱砂「现在」横线标出这一刻 —— 线上方是今天已经过去的（淡掉），
 * 线下第一条是「下一条」（放大）。所以没有单独的「已完成」区：做完的就留在它当天的位置上，
 * 过了今天就不再出现。
 *
 * 操作：轴上的圈 = 完成；点整行 = 编辑；左滑 = 删除。完成和删除都不弹确认，底下给 5 秒「撤销」。
 * 红字只给两种情况：过点没响、闹钟没排上（和小组件「过点写红字」同一个语义，§8.2）。
 */
@Composable
fun ReminderListScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit
) {
    val colors = DaodianColors.current
    val reminders by vm.reminders.collectAsState()

    // 「还有多久」「过点多久」和现在线的位置都跟着钟走
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    // 点过「重排一次」之后重新问一遍 AlarmManager
    var armedProbe by remember { mutableIntStateOf(0) }
    val timeline = remember(reminders, now, armedProbe) {
        buildTimeline(reminders, now, ZoneId.systemDefault(), vm::isArmed)
    }

    var undo by remember { mutableStateOf<Undo?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(5_000)
            undo = null
        }
    }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title = "提醒", onBack = onBack) {
                Box(
                    Modifier
                        .padding(end = 3.dp)
                        .size(38.dp)
                        .background(colors.solid, CircleShape)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center
                ) { PlusIcon(tint = colors.onSolid) }
            }

            if (timeline.lines.isEmpty()) {
                EmptyState(onTalk = onBack, onAdd = onAdd)
                return@Column
            }

            if (timeline.unarmed > 0) {
                UnarmedBanner(timeline.unarmed) {
                    vm.rescheduleAll().invokeOnCompletion { armedProbe++ }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 24.dp, bottom = 96.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(timeline.lines, key = { it.key }) { line ->
                    when (line) {
                        is Line.Day -> DayHeader(line)
                        is Line.Now -> NowLine(line.at)
                        is Line.Entry -> Box(
                            Modifier.animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.flow(),
                                fadeOutSpec = Motion.exit()
                            )
                        ) {
                            SwipeToDelete(
                                onDelete = {
                                    vm.delete(line.r)
                                    undo = Undo("已删除「${line.r.title}」", line.r)
                                }
                            ) {
                                EntryRow(
                                    e = line,
                                    now = now,
                                    onClick = { onEdit(line.r.id) },
                                    onComplete = {
                                        vm.markDone(line.r)
                                        undo = Undo("已完成「${line.r.title}」", line.r)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        UndoBar(
            undo = undo,
            onUndo = {
                undo?.let { vm.restore(it.original) }
                undo = null
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ---------------- 时间轴的数据形状 ----------------

private enum class Kind {
    /** 今天做完的 */
    Done,
    /** 今天响过、还没人点完成 */
    Rang,
    Cancelled,
    /** 还挂着 SCHEDULED 但已经过点 —— 闹钟被掐了，要被看见 */
    Overdue,
    /** 现在线下第一条 */
    Next,
    Upcoming,
    /** 当天事项：没有钟点，排在它那一天的最上面。今天的（含拖过来的）在「今天」抬头底下 */
    DayTask
}

private sealed interface Line {
    val key: Any

    data class Day(val date: LocalDate, val word: String, val caption: String, val first: Boolean) : Line {
        override val key: Any get() = "day-$date"
    }

    data class Now(val at: Long) : Line {
        override val key: Any get() = "now"
    }

    data class Entry(val r: Reminder, val kind: Kind, val armed: Boolean) : Line {
        override val key: Any get() = r.id
    }
}

private class Timeline(val lines: List<Line>, val unarmed: Int)

private data class Undo(val label: String, val original: Reminder)

private val weekdays = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

private fun dayLine(date: LocalDate, today: LocalDate, first: Boolean): Line.Day {
    val wd = weekdays[date.dayOfWeek.value - 1]
    val md = "${date.monthValue}月${date.dayOfMonth}日"
    val days = date.toEpochDay() - today.toEpochDay()
    return when {
        days == 0L -> Line.Day(date, "今天", "$md $wd", first)
        days == 1L -> Line.Day(date, "明天", "$md $wd", first)
        days in 2..6 -> Line.Day(date, wd, md, first)
        else -> Line.Day(date, md, wd, first)
    }
}

/**
 * 今天：已过去的（今天改过状态的非 SCHEDULED）→ 过点没响的 → 现在线 → 今天还没到的；
 * 之后每天一个抬头。过了今天的完成记录不再出现 —— 投递日志里有据可查。
 */
private fun buildTimeline(
    all: List<Reminder>,
    now: Long,
    zone: ZoneId,
    isArmed: (Long) -> Boolean
): Timeline {
    val today = dateOf(now, zone)

    val past = all
        .filter { it.status != ReminderStatus.SCHEDULED && dateOf(it.updatedAt, zone) == today }
        .sortedBy { minOf(it.nextTriggerAt, it.updatedAt) }
        .map {
            val kind = when (it.status) {
                ReminderStatus.FIRED -> Kind.Rang
                ReminderStatus.CANCELLED -> Kind.Cancelled
                else -> Kind.Done
            }
            Line.Entry(it, kind, armed = true)
        }

    val scheduled = all.filter { it.status == ReminderStatus.SCHEDULED }.sortedBy { it.nextTriggerAt }

    // 当天事项按「算哪天的」摆，不按闹钟挂在哪 —— 顺延过的闹钟在明晚，人还得今天看见它。
    // 拖得最久的排最前
    val dayTasks = scheduled.filter { it.isAllDay }
        .sortedWith(compareBy({ it.dueDay }, { it.createdAt }))
        .map { Line.Entry(it, Kind.DayTask, armed = it.nextTriggerAt <= now || isArmed(it.id)) }
    val (dayTasksToday, dayTasksLater) = dayTasks.partition { !(it.r.dueDate() ?: today).isAfter(today) }

    val timed = scheduled.filterNot { it.isAllDay }
    val overdue = timed.filter { it.nextTriggerAt <= now }.map { Line.Entry(it, Kind.Overdue, armed = true) }
    val future = timed.filter { it.nextTriggerAt > now }.mapIndexed { i, r ->
        Line.Entry(r, if (i == 0) Kind.Next else Kind.Upcoming, armed = isArmed(r.id))
    }

    if (past.isEmpty() && overdue.isEmpty() && future.isEmpty() && dayTasks.isEmpty()) return Timeline(emptyList(), 0)

    val laterByDay = dayTasksLater.groupBy { it.r.dueDate() ?: today }
    val futureByDay = future.groupBy { dateOf(it.r.nextTriggerAt, zone) }
    val lines = buildList {
        add(dayLine(today, today, first = true))
        addAll(dayTasksToday)
        addAll(past)
        addAll(overdue)
        add(Line.Now(now))
        addAll(futureByDay[today].orEmpty())
        (laterByDay.keys + futureByDay.keys).filter { it.isAfter(today) }.sorted().forEach { date ->
            add(dayLine(date, today, first = false))
            addAll(laterByDay[date].orEmpty())
            addAll(futureByDay[date].orEmpty())
        }
    }
    return Timeline(lines, unarmed = (future + dayTasks).count { !it.armed })
}

// ---------------- 轴 ----------------

private val TimeColumn = 58.dp
private val AxisColumn = 36.dp

/** 一行 = 左边时刻 + 中间一段轴（带节点）+ 右边内容。轴线画在每一行的底下，连起来就是一根 */
@Composable
private fun AxisRow(
    time: String,
    timeStyle: TextStyle,
    timeColor: Color,
    timeTop: Dp,
    node: @Composable () -> Unit,
    onNode: (() -> Unit)?,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.paper)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Text(
            time, style = timeStyle, color = timeColor, textAlign = TextAlign.End, softWrap = false,
            modifier = Modifier.width(TimeColumn).padding(top = timeTop)
        )
        Box(
            Modifier
                .width(AxisColumn)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    drawLine(colors.rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .size(AxisColumn, 44.dp)
                    .then(if (onNode != null) Modifier.clickable(onClick = onNode) else Modifier),
                contentAlignment = Alignment.Center
            ) { node() }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun EntryRow(e: Line.Entry, now: Long, onClick: () -> Unit, onComplete: () -> Unit) {
    val colors = DaodianColors.current
    val r = e.r
    val rrule = remember(r.rrule) { Format.humanRrule(r.rrule) }
    // 已经过去的那几条排在现在线上方，写它真正落定的时刻：提前做完的写完成时间，别把明天的 09:00 挂在今天
    val clock = Format.clock(
        if (e.kind == Kind.Done || e.kind == Kind.Cancelled) minOf(r.nextTriggerAt, r.updatedAt) else r.nextTriggerAt
    )

    when (e.kind) {
        Kind.Done, Kind.Cancelled, Kind.Rang -> AxisRow(
            time = clock, timeStyle = DaodianType.axisTime, timeColor = colors.hint, timeTop = 12.dp,
            node = {
                when (e.kind) {
                    Kind.Rang -> HollowNode(14.dp, colors.rule2)
                    Kind.Cancelled -> PastNode { Box(Modifier.size(6.dp, 1.2.dp).background(colors.hint)) }
                    else -> PastNode { CheckIcon(size = 8.dp, tint = colors.hint, strokeWidth = 1.3.dp) }
                }
            },
            // 响过没点的还能补一下「完成」；已完成、已取消的圈不接手
            onNode = if (e.kind == Kind.Rang) onComplete else null,
            onClick = onClick
        ) {
            Column(Modifier.padding(top = 10.dp, bottom = 10.dp)) {
                Text(
                    r.title, style = DaodianType.axisTitlePast,
                    color = if (e.kind == Kind.Rang) colors.ink2 else colors.hint,
                    textDecoration = if (e.kind == Kind.Cancelled) TextDecoration.LineThrough else null
                )
                if (e.kind == Kind.Rang) {
                    Text(
                        "响过了，还没点完成", style = DaodianType.caption, color = colors.muted,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        Kind.Next -> AxisRow(
            time = clock, timeStyle = DaodianType.axisTimeNext, timeColor = colors.ink, timeTop = 12.dp,
            node = { HollowNode(20.dp, colors.ink, stroke = 1.4.dp) },
            onNode = onComplete,
            onClick = onClick
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 18.dp)) {
                Text(r.title, style = DaodianType.cardTitle, color = colors.ink)
                Text(
                    Format.span(r.nextTriggerAt - now) + "后",
                    style = DaodianType.caption, color = colors.muted,
                    modifier = Modifier.padding(top = 4.dp)
                )
                EntryNotes(rrule = rrule, armed = e.armed)
            }
        }

        Kind.DayTask -> {
            val today = LocalDate.now()
            val due = r.dueDate() ?: today
            val carried = due.isBefore(today)
            // 闹钟本该在收尾时刻响过、却还挂在过去 —— 和定时提醒的「过点没响」同一个告警
            val missed = r.nextTriggerAt <= now
            AxisRow(
                time = if (carried) "拖${today.toEpochDay() - due.toEpochDay()}天" else "当天",
                timeStyle = DaodianType.axisTime,
                timeColor = if (carried) colors.ink else colors.muted,
                timeTop = 12.dp,
                node = { HollowNode(14.dp, if (missed) colors.red else if (carried) colors.ink2 else colors.rule2) },
                onNode = onComplete,
                onClick = onClick
            ) {
                Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                    Text(r.title, style = DaodianType.rowTitle, color = colors.ink)
                    when {
                        missed -> Text(
                            "晚上该提醒的那次没有响", style = DaodianType.caption, color = colors.red,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        // 今晚不会再响了（提醒过了，或者是过了收尾时刻才记的）：说一声下次什么时候，不然像是被漏掉了
                        due == today && dateOf(r.nextTriggerAt, ZoneId.systemDefault()).isAfter(today) && r.rrule == null -> Text(
                            "今晚不再提醒，没做完明晚 ${Format.clock(r.nextTriggerAt)} 提醒",
                            style = DaodianType.caption, color = colors.muted,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        carried -> Text(
                            "${due.monthValue}月${due.dayOfMonth}日的事，" +
                                (if (dateOf(r.nextTriggerAt, ZoneId.systemDefault()) == today) "今晚" else "明晚") +
                                " ${Format.clock(r.nextTriggerAt)} 再提醒",
                            style = DaodianType.caption, color = colors.muted,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    EntryNotes(rrule = rrule, armed = e.armed)
                }
            }
        }

        Kind.Upcoming, Kind.Overdue -> {
            val overdue = e.kind == Kind.Overdue
            AxisRow(
                time = clock, timeStyle = DaodianType.axisTime,
                timeColor = if (overdue) colors.red else colors.ink, timeTop = 12.dp,
                node = { HollowNode(14.dp, if (overdue) colors.red else colors.rule2) },
                onNode = onComplete,
                onClick = onClick
            ) {
                Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                    Text(r.title, style = DaodianType.rowTitle, color = colors.ink)
                    if (overdue) {
                        Text(
                            "过点 ${Format.span(now - r.nextTriggerAt)}，没有响",
                            style = DaodianType.caption, color = colors.red,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    EntryNotes(rrule = rrule, armed = e.armed)
                }
            }
        }
    }
}

/** 标题下面那一行小字：重复规则、没排上的告警。都没有就什么也不画 */
@Composable
private fun EntryNotes(rrule: String?, armed: Boolean) {
    val colors = DaodianColors.current
    rrule?.let {
        Row(
            Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            RepeatIcon(tint = colors.muted)
            Text(it, style = DaodianType.caption, color = colors.muted)
        }
    }
    if (!armed) {
        Text(
            "没排上闹钟 —— 这条到点不会响", style = DaodianType.caption, color = colors.red,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/** 还没做的：空心圈，点一下就完成。底色填纸色，把轴线盖住 */
@Composable
private fun HollowNode(size: Dp, color: Color, stroke: Dp = 1.2.dp) {
    Box(
        Modifier
            .size(size)
            .background(DaodianColors.current.paper, CircleShape)
            .border(stroke, color, CircleShape)
    )
}

/** 已经过去的：小一号的圈，里面一个淡记号 */
@Composable
private fun PastNode(mark: @Composable () -> Unit) {
    val colors = DaodianColors.current
    Box(
        Modifier
            .size(12.dp)
            .background(colors.paper, CircleShape)
            .border(1.dp, colors.rule2, CircleShape),
        contentAlignment = Alignment.Center
    ) { mark() }
}

@Composable
private fun DayHeader(d: Line.Day) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (d.first) Modifier
                else Modifier.drawBehind {
                    drawLine(colors.ruleSoft, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                }
            )
            .padding(start = 4.dp, top = if (d.first) 10.dp else 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(d.word, style = DaodianType.screenTitle, color = colors.ink)
        Text(d.caption, style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** 朱砂「现在」：一个点、一行小字、一根淡横线。整页唯一的朱砂 */
@Composable
private fun NowLine(at: Long) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            Format.clock(at), style = DaodianType.nowTime, color = colors.accent, textAlign = TextAlign.End,
            modifier = Modifier.width(TimeColumn)
        )
        Box(
            Modifier
                .width(AxisColumn)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    drawLine(colors.rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                },
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(6.dp).background(colors.accent, CircleShape)) }
        Text("现在", style = DaodianType.alarmTag, color = colors.accent)
        Box(
            Modifier
                .padding(start = 8.dp)
                .weight(1f)
                .height(1.dp)
                .background(colors.accent.copy(alpha = 0.35f))
        )
    }
}

// ---------------- 删除 / 撤销 / 告警 / 空状态 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val colors = DaodianColors.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(colors.surfaceAlt)
                    .padding(end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrashIcon(size = 15.dp, tint = colors.red)
                Text("删除", style = DaodianType.button, color = colors.red)
            }
        }
    ) { content() }
}

/** 墨色的撤销条。实心块一律是墨色（§8.1 第 1 条） */
@Composable
private fun UndoBar(undo: Undo?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    // 淡出的那几百毫秒里 undo 已经是 null 了，字要留着
    var shown by remember { mutableStateOf("") }
    if (undo != null) shown = undo.label

    AnimatedVisibility(
        visible = undo != null,
        enter = fadeIn(Motion.settle()) + slideInVertically(Motion.settle()) { it / 2 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 2 },
        modifier = modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .shadow(12.dp, RoundedCornerShape(25.dp), ambientColor = colors.ink, spotColor = colors.ink)
                .background(colors.solid, RoundedCornerShape(25.dp))
                .padding(start = 22.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(shown, style = DaodianType.bodySmall, color = colors.onSolid, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = 44.dp).clickable(onClick = onUndo).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("撤销", style = DaodianType.button, color = colors.onSolid)
            }
        }
    }
}

@Composable
private fun UnarmedBanner(count: Int, onReschedule: () -> Unit) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .drawBehind {
                drawLine(colors.rule, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                drawLine(colors.rule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).background(colors.red, CircleShape))
        Text(
            "有 $count 条没排上闹钟，到点不会响", style = DaodianType.caption, color = colors.red,
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        Box(
            Modifier.heightIn(min = 44.dp).clickable(onClick = onReschedule).padding(start = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("重排一次", style = DaodianType.caption, color = colors.ink2, textDecoration = TextDecoration.Underline)
        }
    }
}

/** 和对话页空状态同一个语汇：一句宋体大字，底下两条细线分隔的去处 */
@Composable
private fun EmptyState(onTalk: () -> Unit, onAdd: () -> Unit) {
    val colors = DaodianColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(84.dp))
        Text("眼下——", style = DaodianType.greetingSoft, color = colors.muted)
        Text("没有要记着的事。", style = DaodianType.greeting, color = colors.ink)
        Spacer(Modifier.height(52.dp))
        Text("想加一条", style = DaodianType.sectionLabel, color = colors.muted, modifier = Modifier.padding(bottom = 6.dp))
        EmptyLink("一", "回对话页，说一句", onTalk, last = false)
        EmptyLink("二", "不经过 AI，手动填一条", onAdd, last = true)
    }
}

@Composable
private fun EmptyLink(ordinal: String, text: String, onClick: () -> Unit, last: Boolean) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(colors.rule, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                if (last) drawLine(colors.rule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(ordinal, style = DaodianType.ordinal, color = colors.hint)
        Text(text, style = DaodianType.body, color = colors.ink2, modifier = Modifier.weight(1f))
        ChevronRightIcon(tint = colors.muted)
    }
}
