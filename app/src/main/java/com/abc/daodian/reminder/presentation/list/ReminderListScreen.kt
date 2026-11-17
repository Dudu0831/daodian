package com.abc.daodian.reminder.presentation.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.gentle
import com.abc.daodian.reminder.data.nudgeDays
import com.abc.daodian.reminder.data.untimed
import com.abc.daodian.reminder.data.untimedDay
import com.abc.daodian.reminder.domain.Rrule
import com.abc.daodian.reminder.presentation.ReminderViewModel
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.Motion
import com.abc.daodian.shared.ui.CheckIcon
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.PlusIcon
import com.abc.daodian.shared.ui.RepeatIcon
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SwipeToDelete
import com.abc.daodian.shared.ui.UndoBar
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 提醒列表 · 时间轴。见 DESIGN.md §08，设计稿「方向 B」：
 * https://claude.ai/code/artifact/4de04ade-2aa5-4486-b1b7-293ff283f00d
 *
 * 一根竖线从今天早上走到以后，朱砂「现在」横线标出这一刻 —— 线上方是今天已经过去的（淡掉），
 * 线上方紧挨着的是「下一条」（放大）。所以没有单独的「已完成」区：做完的就留在它当天的位置上。
 *
 * 整根轴像滚轮（设计稿：https://claude.ai/artifact/3EYZp9KRSBgaXc9Ec92tmG）：从上往下是
 * 以后 → 今天 → 以前，今天之内也是晚的在上。打开时「现在」停在屏幕 [Focus] 高度（以后的不够撑时贴顶，
 * 顶上写一句「以后还没有安排」）。屏幕中间一大段是清楚的，只有靠上下边那一截变淡（「宽焦带」，
 * 设计稿方向 B；不虚化、不缩放）。上下都能一直滑，前几天的记录滑到就在，没有「更多」按钮。
 *
 * 11 月重画（设计稿「版本三」：https://claude.ai/artifact/9DoWbyCPStsXswbFn8GkbG）：线从最远的那件事一直通到过去，
 * 不被任何抬头切开。左边一栏只放刻度 —— 一周以内是「今天」「明天」这样的一道，再远的只标月份、那一段线画成虚的
 * （隔得远，没按比例）；事情挂在右边，时间写在标题上面一行，只写具体的钟点和日期。
 * 日子还远的当天事项（倒数，DESIGN §4.4）就挂在它那一天；提前提醒的那天，「今天」底下多一行淡的、没有圈的。
 *
 * 操作：轴上的圈 = 完成；点整行 = 编辑；左滑 = 删除。完成和删除都不弹确认，底下给 5 秒「撤销」。
 * 红字只给两种情况：过点没响、闹钟没排上（和小组件「过点写红字」同一个语义，§8.2）。
 */
@Composable
fun ReminderListScreen(
    vm: ReminderViewModel,
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
    // 新建 / 改动后，行先从 Room 冒出来、闹钟晚一拍才排上 —— 隔一会儿再问一遍，别先报一句「没排上」
    LaunchedEffect(reminders) {
        delay(600)
        armedProbe++
    }
    val checkTime by vm.checkTime.collectAsState()
    val timeline = remember(reminders, now, armedProbe, checkTime) {
        buildTimeline(reminders, now, ZoneId.systemDefault(), checkTime, vm::isArmed)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nowIndex = timeline.lines.indexOfFirst { it is Line.Now }

    // 「现在」实际停下的高度（占屏幕的比例）。以后的提醒不够把它撑到 [Focus] 时今天贴顶，停得更高
    var focus by remember { mutableFloatStateOf(Focus) }
    // 进来先把「现在」摆到焦点上。只摆一次：之后用户滑到哪就在哪
    var placed by remember { mutableStateOf(false) }
    // 「现在」上面有哪几行：位置号不够 —— 删掉最后一条以后的，顶上又冒出「以后还没有安排」，一减一加号码不变
    val aboveNow = remember(timeline) { timeline.lines.take(maxOf(nowIndex, 0)).map { it.key } }
    LaunchedEffect(aboveNow) {
        if (nowIndex < 0) return@LaunchedEffect
        if (!placed) {
            listState.scrollToItem(nowIndex)
            listState.centerOnFocus(nowIndex, Focus, animated = false)
            listState.centerFraction(nowIndex)?.let { focus = minOf(Focus, it) }
            placed = true
        } else if (listState.centerFraction(nowIndex) != null) {
            // 人正看着今天，上面多了 / 少了一条（新建、删掉以后的、「以后还没有安排」出现或消失）：
            // 把「现在」重新摆回去，贴顶时就回到顶上，别让今天停在半截被宽焦带淡掉
            listState.centerOnFocus(nowIndex, Focus, animated = true)
            listState.centerFraction(nowIndex)?.let { focus = minOf(Focus, it) }
        }
    }
    // 「现在」滑出焦点半屏以外，右下角给一个「回到今天」
    val awayFromNow by remember(nowIndex) {
        derivedStateOf {
            val at = listState.centerFraction(nowIndex)
            at == null || abs(at - focus) > 0.5f
        }
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

            BoxWithConstraints(Modifier.fillMaxSize()) {
            val fade = 72.dp
            LazyColumn(
                state = listState,
                // 顶上不垫：以后的提醒不够把「现在」撑到焦点时，今天就贴着顶，不留一块空白。
                // 底下垫到焦点，让最早的记录也能滑上来看清
                contentPadding = PaddingValues(start = 14.dp, end = 24.dp, bottom = maxHeight * (1 - Focus)),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(timeline.lines, key = { _, it -> it.key }) { index, line ->
                    Box(
                        Modifier
                            .then(
                                if (line is Line.Entry) Modifier.animateItem(
                                    fadeInSpec = Motion.settle(),
                                    placementSpec = Motion.flow(),
                                    fadeOutSpec = Motion.exit()
                                ) else Modifier
                            )
                            .wheel(listState, index)
                    ) {
                    when (line) {
                        is Line.Day -> DayTick(line)
                        is Line.Month -> MonthTick(line)
                        is Line.Echo -> EchoRow(line, onClick = { onEdit(line.r.id) })
                        is Line.Now -> NowLine(line.at)
                        is Line.End -> EndMark()
                        is Line.AheadEmpty -> AheadEmpty()
                        is Line.Entry -> Box {
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
                                        vm.complete(line.r)
                                        undo = Undo("已完成「${line.r.title}」", line.r)
                                    }
                                )
                            }
                        }
                    }
                    }
                }
            }
            // 滚轮的两头：纸色渐隐。顶上那条只在上面还有东西时才有，贴顶时别把今天的抬头盖淡
            val topFade by animateFloatAsState(if (listState.canScrollBackward) 1f else 0f, Motion.settle())
            Box(
                Modifier.fillMaxWidth().height(fade).align(Alignment.TopCenter)
                    .graphicsLayer { alpha = topFade }
                    .background(Brush.verticalGradient(listOf(colors.paper, colors.paper.copy(alpha = 0f))))
            )
            Box(
                Modifier.fillMaxWidth().height(fade).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(colors.paper.copy(alpha = 0f), colors.paper)))
            )
            }
        }

        BackToNow(
            visible = awayFromNow && undo == null && nowIndex >= 0,
            onClick = { scope.launch { listState.centerOnFocus(nowIndex, focus, animated = true) } },
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        UndoBar(
            label = undo?.label,
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

    /** 近处（前后 [NearDays] 天以内）的一天：线上一道刻度，左边「今天」「明天」「周六」，右边几日和星期 */
    data class Day(val date: LocalDate, val word: String, val caption: String) : Line {
        override val key: Any get() = "day-$date"
    }

    /** 远处的月份刻度。[ahead] 只为了 key：今天在月中时，同一个月可能在以后和以前各出现一次 */
    data class Month(val month: YearMonth, val showYear: Boolean, val ahead: Boolean) : Line {
        override val key: Any get() = "month-$month-$ahead"
    }

    /**
     * 提前提醒的那天（§4.4），挂在「今天」底下的一行淡的：不是另一条，点它就是点远处那一行；没有圈。
     * [at] = 今晚提的那一刻，[rang] = 已经提过了
     */
    data class Echo(val r: Reminder, val at: Long, val rang: Boolean) : Line {
        override val key: Any get() = "echo-${r.id}"
    }

    data class Now(val at: Long) : Line {
        override val key: Any get() = "now"
    }

    /** [far] = 在远处：没有当天的刻度，自己带着几日和星期，那一段线是虚的 */
    data class Entry(val r: Reminder, val kind: Kind, val armed: Boolean, val far: Boolean = false) : Line {
        override val key: Any get() = r.id
    }

    /** 轴的顶：现在之后什么都没排时，代替那块空白 */
    data object AheadEmpty : Line {
        override val key: Any get() = "ahead-empty"
    }

    /** 轴的底：最早的以前之下 */
    data object End : Line {
        override val key: Any get() = "end"
    }
}

private class Timeline(val lines: List<Line>, val unarmed: Int)

private data class Undo(val label: String, val original: Reminder)

private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** 前后这么多天以内算近处，有自己的一道刻度；再远的只标月份 */
private const val NearDays = 6L

private fun dayLine(date: LocalDate, today: LocalDate): Line.Day {
    val wd = Format.weekday(date.dayOfWeek)
    val d = "${date.dayOfMonth}日"
    return when (date.toEpochDay() - today.toEpochDay()) {
        0L -> Line.Day(date, "今天", "$d $wd")
        1L -> Line.Day(date, "明天", "$d $wd")
        -1L -> Line.Day(date, "昨天", "$d $wd")
        2L -> Line.Day(date, "后天", "$d $wd")
        -2L -> Line.Day(date, "前天", "$d $wd")
        else -> Line.Day(date, wd, d)
    }
}

/** 已经落定的那一刻：提前做完的算完成时间，别把明天的 09:00 算成它的时刻 */
private fun settledAt(r: Reminder): Long = minOf(r.nextTriggerAt, r.updatedAt)

private fun pastEntry(r: Reminder): Line.Entry {
    val kind = when (r.status) {
        ReminderStatus.FIRED -> Kind.Rang
        ReminderStatus.CANCELLED -> Kind.Cancelled
        else -> Kind.Done
    }
    return Line.Entry(r, kind, armed = true)
}

/**
 * 整根轴倒着排：最远的以后在最上，最早的以前在最下，一天之内也是晚的在上。
 * 每天：抬头 → 当天事项（没有钟点，挂在抬头底下）→ 带钟点的从晚到早。
 * 今天：抬头 → 当天事项 → 今天还没到的 → 现在线 → 过点没响的和已经落定的（一起按时刻倒排）。
 * 以前的日子收的是落定的记录（完成、取消、响过没点），按改状态那天分组。
 */
private fun buildTimeline(
    all: List<Reminder>,
    now: Long,
    zone: ZoneId,
    checkTime: LocalTime,
    isArmed: (Long) -> Boolean
): Timeline {
    val today = dateOf(now, zone)

    val settledByDay = all.filter { it.status != ReminderStatus.SCHEDULED }.groupBy { dateOf(it.updatedAt, zone) }
    val scheduled = all.filter { it.status == ReminderStatus.SCHEDULED }.sortedBy { it.nextTriggerAt }

    // 当天事项按「算哪天的」摆，不按闹钟挂在哪 —— 顺延过的闹钟在明晚，人还得今天看见它。
    // 拖得最久的排最前
    val dayTasks = scheduled.filter { it.untimed }
        .sortedWith(compareBy({ it.day }, { it.createdAt }))
        .map { Line.Entry(it, Kind.DayTask, armed = it.nextTriggerAt <= now || isArmed(it.id)) }
    val dayTasksByDay = dayTasks.groupBy { maxOf(it.r.day, today) }

    val timed = scheduled.filterNot { it.untimed }
    val overdue = timed.filter { it.nextTriggerAt <= now }.map { Line.Entry(it, Kind.Overdue, armed = true) }
    val future = timed.filter { it.nextTriggerAt > now }.mapIndexed { i, r ->
        Line.Entry(r, if (i == 0) Kind.Next else Kind.Upcoming, armed = isArmed(r.id))
    }
    val futureByDay = future.groupBy { dateOf(it.r.nextTriggerAt, zone) }

    // 今天现在线以下：过点没响的 + 今天落定的，按各自的时刻一起倒排
    val todayPast = (overdue.map { it to it.r.nextTriggerAt } +
        settledByDay[today].orEmpty().map { pastEntry(it) to settledAt(it) })
        .sortedByDescending { it.second }
        .map { it.first }

    val days = (settledByDay.keys + dayTasksByDay.keys + futureByDay.keys + today).sortedDescending()
    if (days.size == 1 && todayPast.isEmpty() && dayTasksByDay[today] == null && futureByDay[today] == null) {
        return Timeline(emptyList(), 0)
    }

    // 今天是提前提醒的日子（§4.6）：那件事还挂在远处它自己那天，今天底下多一行淡的
    val echoes = scheduled.filter { r ->
        r.untimed && r.day.isAfter(today) && today in r.nudgeDays()
    }.map { r ->
        val at = today.atTime(checkTime).atZone(zone).toInstant().toEpochMilli()
        Line.Echo(r, at, rang = at <= now)
    }

    val lines = buildList {
        if (future.isEmpty() && dayTasksByDay.keys.none { it.isAfter(today) }) add(Line.AheadEmpty)
        var month: YearMonth? = null
        days.forEach { date ->
            val far = abs(date.toEpochDay() - today.toEpochDay()) > NearDays
            if (far) {
                val ym = YearMonth.from(date)
                if (ym != month) add(Line.Month(ym, showYear = ym.year != today.year, ahead = date.isAfter(today)))
                month = ym
            } else {
                month = null
                add(dayLine(date, today))
            }
            addAll(dayTasksByDay[date].orEmpty().map { it.copy(far = far) })
            addAll(futureByDay[date].orEmpty().asReversed().map { it.copy(far = far) })
            when {
                date == today -> {
                    addAll(echoes.filter { !it.rang })
                    add(Line.Now(now))
                    addAll(echoes.filter { it.rang })
                    addAll(todayPast)
                }
                date.isBefore(today) ->
                    addAll(settledByDay.getValue(date).sortedByDescending(::settledAt).map { pastEntry(it).copy(far = far) })
            }
        }
        add(Line.End)
    }
    return Timeline(lines, unarmed = (future + dayTasks).count { !it.armed })
}

// ---------------- 轴 ----------------

private val LabelColumn = 44.dp
private val AxisColumn = 30.dp

/**
 * 线画在每一行自己的那一段上，行和行之间没有缝，连起来就是不断的一根。[dashed] = 远处那一段（没按比例）。
 * [tick] > 0 时在这一段的正中横一道刻度
 */
private fun Modifier.axis(color: Color, dashed: Boolean, tick: Dp = 0.dp, tickColor: Color = color, tickStroke: Dp = 1.dp): Modifier =
    drawBehind {
        val x = size.width / 2
        drawLine(
            color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())) else null
        )
        if (tick > 0.dp) {
            val half = tick.toPx() / 2
            drawLine(tickColor, Offset(x - half, size.height / 2), Offset(x + half, size.height / 2), strokeWidth = tickStroke.toPx())
        }
    }

/** 一行 = 左边空着的刻度栏 + 中间一段线（带节点）+ 右边内容 */
@Composable
private fun AxisRow(
    far: Boolean,
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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        // 圈好点一些：手指落在刻度栏靠线的这一半也算
        Box(
            Modifier
                .width(LabelColumn + AxisColumn)
                .fillMaxHeight()
                .padding(start = LabelColumn)
                .axis(colors.rule2, dashed = far),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .size(AxisColumn, 44.dp)
                    .then(if (onNode != null) Modifier.clickable(onClick = onNode) else Modifier),
                contentAlignment = Alignment.Center
            ) { node() }
        }
        Box(Modifier.weight(1f).padding(start = 4.dp)) { content() }
    }
}

/** 标题上面那一行：几点，远处的是「13日 周六」 */
@Composable
private fun Stamp(text: String, color: Color) {
    Text(text, style = DaodianType.caption, color = color, modifier = Modifier.padding(bottom = 1.dp))
}

@Composable
private fun EntryRow(e: Line.Entry, now: Long, onClick: () -> Unit, onComplete: () -> Unit) {
    val colors = DaodianColors.current
    val r = e.r
    val rrule = remember(r.rrule) { Rrule.human(r.rrule) }
    val zone = ZoneId.systemDefault()
    // 已经过去的那几条排在现在线上方，写它真正落定的时刻：提前做完的写完成时间，别把明天的 09:00 挂在今天
    val at = if (e.kind == Kind.Done || e.kind == Kind.Cancelled) settledAt(r) else r.nextTriggerAt
    val clock = Format.clock(at)
    // 近处的头上有「今天」「明天」那道刻度，只写钟点；远处的自己带着几日和星期
    fun stamp(day: LocalDate, clock: String?): String =
        if (e.far) listOfNotNull("${day.dayOfMonth}日 ${Format.weekday(day.dayOfWeek)}", clock).joinToString(" ")
        else clock ?: "当天"

    when (e.kind) {
        Kind.Done, Kind.Cancelled, Kind.Rang -> AxisRow(
            far = e.far,
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
            Column(Modifier.padding(top = 9.dp, bottom = 10.dp)) {
                Stamp(stamp(dateOf(at, zone), clock), colors.hint)
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
            far = e.far,
            node = { HollowNode(20.dp, colors.ink, stroke = 1.4.dp) },
            onNode = onComplete,
            onClick = onClick
        ) {
            Column(Modifier.padding(top = 9.dp, bottom = 16.dp)) {
                Stamp(stamp(dateOf(at, zone), clock) + " · " + Format.span(r.nextTriggerAt - now) + "后", colors.ink)
                Text(r.title, style = DaodianType.cardTitle, color = colors.ink)
                EntryNotes(rrule = rrule, armed = e.armed, gentle = r.gentle)
            }
        }

        Kind.DayTask -> {
            val today = LocalDate.now()
            val due = r.day
            val carried = due.isBefore(today)
            // 闹钟本该在收尾时刻响过、却还挂在过去 —— 和定时提醒的「过点没响」同一个告警
            val missed = r.nextTriggerAt <= now
            val ringDay = dateOf(r.nextTriggerAt, zone)
            // 日子还没到、闹钟却排在它前面：排着的是提前提醒（§4.6）
            val nudges = if (ringDay.isBefore(due)) r.nudgeDays().count { !it.isBefore(ringDay) && it.isBefore(due) } else 0
            AxisRow(
                far = e.far,
                node = { HollowNode(14.dp, if (missed) colors.red else if (carried) colors.ink2 else colors.rule2) },
                onNode = onComplete,
                onClick = onClick
            ) {
                Column(Modifier.padding(top = 9.dp, bottom = 11.dp)) {
                    Stamp(
                        if (carried) "拖 ${today.toEpochDay() - due.toEpochDay()} 天" else stamp(due, null),
                        if (carried) colors.ink else colors.muted
                    )
                    Text(r.title, style = DaodianType.rowTitle, color = colors.ink)
                    when {
                        missed -> Text(
                            "晚上该提醒的那次没有响", style = DaodianType.caption, color = colors.red,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        nudges > 0 -> Text(
                            "提前提醒 $nudges 次 · 下次 " +
                                (if (ringDay == today) "今晚" else "${ringDay.monthValue}月${ringDay.dayOfMonth}日"),
                            style = DaodianType.caption, color = colors.muted,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        // 今晚不会再响了（提醒过了，或者是过了收尾时刻才记的）：说一声下次什么时候，不然像是被漏掉了
                        due == today && ringDay.isAfter(today) && r.rrule == null -> Text(
                            "今晚不再提醒，没做完明晚 ${Format.clock(r.nextTriggerAt)} 提醒",
                            style = DaodianType.caption, color = colors.muted,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        carried -> Text(
                            "${due.monthValue}月${due.dayOfMonth}日的事，" +
                                (if (ringDay == today) "今晚" else "明晚") +
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
                far = e.far,
                node = { HollowNode(14.dp, if (overdue) colors.red else colors.rule2) },
                onNode = onComplete,
                onClick = onClick
            ) {
                Column(Modifier.padding(top = 9.dp, bottom = 11.dp)) {
                    Stamp(stamp(dateOf(at, zone), clock), if (overdue) colors.red else colors.muted)
                    Text(r.title, style = DaodianType.rowTitle, color = colors.ink)
                    if (overdue) {
                        Text(
                            "过点 ${Format.span(now - r.nextTriggerAt)}，没有响",
                            style = DaodianType.caption, color = colors.red,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    EntryNotes(rrule = rrule, armed = e.armed, gentle = r.gentle)
                }
            }
        }
    }
}

/** 提前提醒的那天挂在「今天」底下的那一行：淡的、没有圈，写今晚几点提、是哪天的事。见 [Line.Echo] */
@Composable
private fun EchoRow(e: Line.Echo, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val due = e.r.untimedDay() ?: return
    AxisRow(
        far = false,
        node = { HollowNode(6.dp, colors.rule2, stroke = 1.dp) },
        onNode = null,
        onClick = onClick
    ) {
        Column(Modifier.padding(top = 9.dp, bottom = 11.dp)) {
            Stamp(Format.clock(e.at) + if (e.rang) " · 提过了" else "", colors.hint)
            Text(e.r.title, style = DaodianType.axisTitlePast, color = colors.ink2)
            Text(
                (if (due.year != LocalDate.now().year) "${due.year}年" else "") + "${due.monthValue}月${due.dayOfMonth}日的事，提一下",
                style = DaodianType.caption, color = colors.muted,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/**
 * 标题下面那一行小字：重复规则、轻提醒、没排上的告警。都没有就什么也不画。
 * [gentle]（轻提醒：到点只发一条通知、不响铃）接在重复规则后面：「每天 · 轻提醒」；不重复的自己占一行。当天事项不传
 */
@Composable
private fun EntryNotes(rrule: String?, armed: Boolean, gentle: Boolean = false) {
    val colors = DaodianColors.current
    val how = listOfNotNull(rrule, "轻提醒".takeIf { gentle }).joinToString(" · ")
    if (how.isNotEmpty()) {
        Row(
            Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (rrule != null) RepeatIcon(tint = colors.muted)
            Text(how, style = DaodianType.caption, color = colors.muted)
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

/** 近处的一天：线上一道墨色的刻度，左边「今天」，右边几日和星期。线从它中间穿过去，不断 */
@Composable
private fun DayTick(d: Line.Day) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            d.word, style = DaodianType.rowTitle, color = colors.ink, textAlign = TextAlign.End, softWrap = false,
            modifier = Modifier.width(LabelColumn)
        )
        Box(
            Modifier.width(AxisColumn).fillMaxHeight()
                .axis(colors.rule2, dashed = false, tick = 11.dp, tickColor = colors.ink, tickStroke = 1.4.dp)
        )
        Text(d.caption, style = DaodianType.caption, color = colors.muted, modifier = Modifier.padding(start = 4.dp))
    }
}

/** 远处的月份：一道短刻度，左边「2月」，跨年的上面标年份。这一段线是虚的 */
@Composable
private fun MonthTick(m: Line.Month) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().height(if (m.showYear) 36.dp else 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(LabelColumn), horizontalAlignment = Alignment.End) {
            if (m.showYear) Text("${m.month.year}", style = ScaleYear, color = colors.hint)
            Text("${m.month.monthValue}月", style = ScaleMonth, color = colors.muted)
        }
        Box(
            Modifier.width(AxisColumn).fillMaxHeight()
                .axis(colors.rule2, dashed = true, tick = 7.dp, tickColor = colors.muted)
        )
    }
}

private val ScaleMonth = DaodianType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em)
private val ScaleYear = DaodianType.caption.copy(fontSize = 9.5.sp, lineHeight = 11.sp, letterSpacing = 0.04.em)

/** 朱砂「现在」：线上的一个点、一行小字、一根淡横线。整页唯一的朱砂；线从点底下穿过去 */
@Composable
private fun NowLine(at: Long) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            Format.clock(at), style = DaodianType.nowTime, color = colors.accent, textAlign = TextAlign.End, softWrap = false,
            modifier = Modifier.width(LabelColumn)
        )
        Box(
            Modifier.width(AxisColumn).fillMaxHeight().axis(colors.rule2, dashed = false),
            contentAlignment = Alignment.Center
        ) {
            // 垫一圈纸色，点和线之间留一点空
            Box(Modifier.size(12.dp).background(colors.paper, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).background(colors.accent, CircleShape))
            }
        }
        Text("现在", style = DaodianType.alarmTag, color = colors.accent, modifier = Modifier.padding(start = 4.dp))
        Box(
            Modifier
                .padding(start = 8.dp)
                .weight(1f)
                .height(1.dp)
                .background(colors.accent.copy(alpha = 0.35f))
        )
    }
}

// ---------------- 滚轮 ----------------

/** 「现在」想停在屏幕的这个高度（从上往下的比例）：偏上，上面露出明天，下面多留点给以前 */
private const val Focus = 0.33f

/**
 * 宽焦带：屏幕 [BandTop]–[BandBottom] 这一大段完全清楚，带外线性淡到 [EdgeAlpha]。
 * 贴顶（上面没东西了）时清楚带从 0 开始，今天不会被当成边缘淡掉。不虚化、不缩放 ——
 * 上一版每行一个 BlurEffect，掉帧又会在行边上透出一圈方框。
 * 只在绘制阶段读 layoutInfo，滑动时不触发重组。
 */
private const val BandTop = 0.18f
private const val BandBottom = 0.78f
private const val EdgeAlpha = 0.3f

private fun Modifier.wheel(state: LazyListState, index: Int): Modifier = graphicsLayer {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
    val h = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val cy = item.offset - info.viewportStartOffset + item.size / 2f
    val a = if (state.canScrollBackward) h * BandTop else 0f
    val b = h * BandBottom
    val t = when {
        cy < a -> (a - cy) / a
        cy > b -> (cy - b) / (h - b)
        else -> 0f
    }.coerceIn(0f, 1f)
    alpha = 1f - (1f - EdgeAlpha) * t
}

/** 第 [index] 行的中线在屏幕多高（比例）；不在屏幕上就是 null */
private fun LazyListState.centerFraction(index: Int): Float? {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val h = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    return (item.offset - info.viewportStartOffset + item.size / 2f) / h
}

/** 把第 [index] 行的中线摆到 [focus] 高度上（滑不过头就停在头上） */
private suspend fun LazyListState.centerOnFocus(index: Int, focus: Float, animated: Boolean) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        if (animated) animateScrollToItem(index) else scrollToItem(index)
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val h = info.viewportEndOffset - info.viewportStartOffset
    val delta = item.offset - info.viewportStartOffset + item.size / 2f - h * focus
    if (animated) animateScrollBy(delta) else scrollBy(delta)
}

/** 以后什么都没排：虚线轴收成一个小空圈，一行淡字 */
@Composable
private fun AheadEmpty() {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(LabelColumn))
        Box(
            Modifier
                .width(AxisColumn)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    drawLine(
                        colors.rule2, Offset(x, size.height / 2), Offset(x, size.height), strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    )
                },
            contentAlignment = Alignment.Center
        ) { HollowNode(6.dp, colors.rule2, stroke = 1.dp) }
        Text("以后还没有安排", style = DaodianType.caption, color = colors.hint)
    }
}

/** 轴的底：一行淡字，告诉人滑到底了 */
@Composable
private fun EndMark() {
    val colors = DaodianColors.current
    Text(
        "再往前没有了",
        style = DaodianType.caption, color = colors.hint, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)
    )
}

/** 滑远了才出来：墨色小胶囊，点一下滑回「现在」 */
@Composable
private fun BackToNow(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.settle()) + slideInVertically(Motion.settle()) { it / 2 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 2 },
        modifier = modifier.navigationBarsPadding().padding(end = 16.dp, bottom = 24.dp)
    ) {
        Box(
            Modifier
                .height(40.dp)
                .shadow(10.dp, RoundedCornerShape(20.dp), ambientColor = colors.ink, spotColor = colors.ink)
                .background(colors.solid, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("回到今天", style = DaodianType.button, color = colors.onSolid)
        }
    }
}

// ---------------- 删除 / 撤销 / 告警 / 空状态 ----------------

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
