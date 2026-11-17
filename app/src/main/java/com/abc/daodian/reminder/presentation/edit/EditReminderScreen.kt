package com.abc.daodian.reminder.presentation.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.abc.daodian.reminder.data.Nag
import com.abc.daodian.reminder.data.ReminderStatus
import com.abc.daodian.reminder.data.Ring
import com.abc.daodian.reminder.data.parseTimes
import com.abc.daodian.reminder.data.remindBeforeColumn
import com.abc.daodian.reminder.data.timesColumn
import com.abc.daodian.reminder.domain.ReminderSpec
import com.abc.daodian.reminder.domain.spec
import com.abc.daodian.reminder.domain.Rrule
import com.abc.daodian.reminder.presentation.ReminderViewModel
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.ChevronRightIcon
import com.abc.daodian.shared.ui.GroupLabel
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.PaperSwitch
import com.abc.daodian.shared.ui.RepeatBadge
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.UndoBar
import com.abc.daodian.reminder.data.ReminderItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import com.abc.daodian.reminder.domain.Checklist
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * [CUSTOM] = 模型建的、这页画不出来的规则（「每周一、三」「每两周」）。
 * 原样保留，不能因为打开编辑页改了个标题就被压扁成「每周」。
 */
enum class RepeatChoice { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM }

private val weekdayCode = arrayOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/**
 * 手动建 / 改一条提醒 —— 逃生舱。必须能完全脱离 AI 建成一条完整提醒。见 DESIGN.md §2.1
 *
 * 版式跟设置页同一本账：宋体标题写在横线上，底下一行人话复述，再往下几组纸，「记下」钉在底部。
 * 一条提醒六件事（§4.6，设计稿 https://claude.ai/artifact/GN6UcNftUvPNAALd8GTVD8）：
 * 「什么时候」一组是哪天、几点（可以不定、可以几个）、重复；「怎么提醒」一组是提前提醒、轻提醒、没点完成，
 * 定了钟点才多一行「跟着我所在时区走」。几点、重复、提前、没点完成各拉一张底纸。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditReminderScreen(vm: ReminderViewModel, reminderId: Long?, onBack: () -> Unit) {
    val colors = DaodianColors.current
    val reminders by vm.reminders.collectAsState()
    val existing = remember(reminders, reminderId) { reminders.firstOrNull { it.id == reminderId } }
    val start = remember(existing) { existing?.spec() }

    var title by rememberSaveable(existing) { mutableStateOf(start?.title ?: "") }
    var note by rememberSaveable(existing) { mutableStateOf(start?.note ?: "") }

    val defaultDateTime = remember { ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0) }
    // 这一次是哪天：拖着的还是原来那天，重复的是排到的那一次
    var date by rememberSaveable(existing) { mutableStateOf(start?.day ?: defaultDateTime.toLocalDate()) }
    // 几点、提前几天存成库里那一列的写法，省得另写 Saver。几点空串 = 不定
    var timesText by rememberSaveable(existing) {
        mutableStateOf(if (start != null) timesColumn(start.times).orEmpty() else timesColumn(listOf(defaultDateTime.toLocalTime()))!!)
    }
    val times = remember(timesText) { parseTimes(timesText.ifEmpty { null }) }
    val originalRrule = start?.rrule
    var repeat by rememberSaveable(existing) { mutableStateOf(initialRepeat(originalRrule, date)) }
    var beforeText by rememberSaveable(existing) { mutableStateOf(remindBeforeColumn(start?.remindBefore.orEmpty()).orEmpty()) }
    var ring by rememberSaveable(existing) { mutableStateOf(start?.ring ?: Ring.ALARM) }
    var nag by rememberSaveable(existing) { mutableStateOf(start?.nag ?: Nag.NONE) }
    var wallClockAnchored by rememberSaveable(existing) { mutableStateOf(start?.wallClockAnchored ?: false) }
    // 新建时，响法和催跟着「几点」「重复」换默认（和模型那边一样）；自己动过那一行就不再跟
    var ringTouched by rememberSaveable(existing) { mutableStateOf(existing != null) }
    var nagTouched by rememberSaveable(existing) { mutableStateOf(existing != null) }

    val checkTime by vm.checkTime.collectAsState()
    val today = remember { LocalDate.now() }

    // 清单（§4.5）：已有的一条当场存进库；新建的先攒在这页上（key 用负数），跟着「记下」一起存
    val savedItems by remember(existing?.id) { existing?.let { vm.items(it.id) } ?: flowOf(emptyList()) }
        .collectAsState(emptyList())
    val draftItems = remember { mutableStateListOf<CheckLine>() }
    var draftKey by remember { mutableStateOf(-1L) }
    val checkLines = if (existing != null) savedItems.map { CheckLine(it.id, it.text, it.done) } else draftItems.toList()
    fun itemOf(line: CheckLine): ReminderItem? = savedItems.firstOrNull { it.id == line.key }
    // 左滑删了一样：5 秒「撤销」
    var undoItem by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(undoItem) { if (undoItem != null) { delay(5000); undoItem = null } }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimeSheet by remember { mutableStateOf(false) }
    var showRepeatSheet by remember { mutableStateOf(false) }
    var showNudgeSheet by remember { mutableStateOf(false) }
    var showNagSheet by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    // 拉底纸前先放掉标题、备注的焦点：不然底纸一收，焦点回到输入框，键盘又弹起来
    val focusManager = LocalFocusManager.current
    fun sheet(open: () -> Unit) { focusManager.clearFocus(); open() }

    fun buildRrule(): String? = when (repeat) {
        RepeatChoice.NONE -> null
        RepeatChoice.DAILY -> "FREQ=DAILY"
        RepeatChoice.WEEKLY -> "FREQ=WEEKLY;BYDAY=${weekdayCode[date.dayOfWeek.value - 1]}"
        RepeatChoice.MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=${date.dayOfMonth}"
        RepeatChoice.YEARLY -> "FREQ=YEARLY"
        RepeatChoice.CUSTOM -> originalRrule
    }

    // 只在说不通时才拦（§4.6）：不弹错，说不通的那几样当场落回去 —— 提前的天数要比两次间隔短，每天都有的不能每晚再提
    val rrule = buildRrule()
    val gap = remember(rrule, date) { Rrule.shortestGap(rrule, date, ZoneId.systemDefault()) }
    val before = remember(beforeText, gap) {
        beforeText.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 && (gap == null || it < gap) }
    }
    val dailyAllowed = gap == null || gap > 1
    val effectiveNag = if (nag == Nag.DAILY && !dailyAllowed) Nag.NONE else nag

    fun followDefaults(newTimes: List<LocalTime>, newRepeat: RepeatChoice) {
        if (!ringTouched && newTimes.isNotEmpty()) ring = Ring.ALARM
        if (!nagTouched) nag = if (newTimes.isEmpty() && newRepeat == RepeatChoice.NONE) Nag.DAILY else Nag.NONE
    }

    fun save() {
        // 先放掉焦点：正在改的那一样（或「加一样」里打了没回车的字）这一下存进来
        focusManager.clearFocus()
        vm.upsertManual(
            existing?.id,
            ReminderSpec(
                title = title.ifBlank { "未命名提醒" },
                note = note.ifBlank { null },
                day = date,
                times = times,
                rrule = rrule,
                remindBefore = before,
                ring = if (times.isEmpty()) Ring.NOTICE else ring,
                nag = effectiveNag,
                // 不定钟点的一律按当地（§4.6）
                wallClockAnchored = times.isEmpty() || wallClockAnchored
            ),
            newItems = if (existing == null) draftItems.map { it.text to it.done } else emptyList()
        )
        onBack()
    }

    val repeatOptions = repeatOptions(date, originalRrule)
    val repeatLabel = repeatOptions.firstOrNull { it.choice == repeat }?.label ?: "不重复"

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = if (existing == null) "新建提醒" else "改一下", onBack = onBack)

        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

                // ---- 标题 + 人话复述 ----
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
                        if (title.isEmpty()) Text("要提醒什么", style = DaodianType.editTitle, color = colors.hint)
                        BasicTextField(
                            value = title, onValueChange = { title = it },
                            textStyle = DaodianType.editTitle.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(if (title.isEmpty()) colors.rule2 else colors.rule))
                    Spacer(Modifier.height(12.dp))
                    Summary(date = date, times = times, checkTime = checkTime, repeatLabel = repeatLabel.takeIf { repeat != RepeatChoice.NONE })
                }

                Column(Modifier.padding(horizontal = 20.dp)) {

                    // ---- 清单：这件事本身的内容，排在所有设置上面（方向甲） ----
                    ChecklistGroup(
                        lines = checkLines,
                        beforeEdit = { focusManager.clearFocus() },
                        onAdd = { text ->
                            if (existing != null) vm.addItem(existing.id, text)
                            else draftItems += CheckLine(draftKey--, text, false)
                        },
                        onToggle = { line ->
                            if (existing != null) itemOf(line)?.let { vm.setItemDone(it, !it.done) }
                            else draftItems.replaceAll { if (it.key == line.key) it.copy(done = !it.done) else it }
                        },
                        onRename = { line, text ->
                            if (existing != null) itemOf(line)?.let { vm.renameItem(it, text) }
                            else Checklist.clean(text).let { t ->
                                if (t == null) draftItems.removeAll { it.key == line.key }
                                else draftItems.replaceAll { if (it.key == line.key) it.copy(text = t) else it }
                            }
                        },
                        onRemove = { line ->
                            if (existing != null) {
                                itemOf(line)?.let { item -> vm.removeItem(item); undoItem = "已删掉「${item.text}」" to { vm.restoreItem(item) } }
                            } else {
                                val at = draftItems.indexOfFirst { it.key == line.key }
                                if (at >= 0) {
                                    draftItems.removeAt(at)
                                    undoItem = "已删掉「${line.text}」" to { draftItems.add(at.coerceAtMost(draftItems.size), line) }
                                }
                            }
                        }
                    )

                    // 快捷钟点只在新建时给 —— 改一条已有的，多半是改一点点，不是整个换掉
                    if (existing == null) {
                        Row(
                            Modifier.padding(top = 18.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            quickPicks().forEach { q ->
                                val picked = listOfNotNull(q.time)
                                Chip(q.label, selected = date == q.date && times == picked) {
                                    date = q.date; timesText = timesColumn(picked).orEmpty()
                                    followDefaults(picked, repeat)
                                }
                            }
                        }
                    }

                    // ---- 什么时候 ----
                    GroupLabel("什么时候")
                    PaperGroup {
                        FormRow(
                            title = if (repeat == RepeatChoice.NONE) "哪天" else "从哪天起",
                            onClick = { sheet { showDatePicker = true } }
                        ) { Value("${relativeDay(date)?.let { "$it · " } ?: ""}${Format.humanDay(date)}") }
                        GroupRule()
                        FormRow(
                            title = "几点",
                            note = if (times.isEmpty()) "那天 ${clock(checkTime)} 提醒" else null,
                            onClick = { sheet { showTimeSheet = true } }
                        ) {
                            Value(
                                when {
                                    times.isEmpty() -> "不定"
                                    times.size <= 4 -> times.joinToString("、") { clock(it) }
                                    else -> "${times.size} 个钟点"
                                }
                            )
                        }
                        GroupRule()
                        FormRow(title = "重复", onClick = { sheet { showRepeatSheet = true } }) { Value(repeatLabel) }
                    }

                    // ---- 怎么提醒 ----
                    GroupLabel("怎么提醒")
                    PaperGroup {
                        if (gap != null && gap <= 1) {
                            FormRow(title = "提前提醒", note = "每天都有的，提前不了", dim = true) {
                                Text("不提前", style = DaodianType.axisTime, color = colors.hint)
                            }
                        } else {
                            FormRow(title = "提前提醒", onClick = { sheet { showNudgeSheet = true } }) {
                                Value(
                                    when {
                                        before.isEmpty() -> "不提前"
                                        before.size <= 3 -> "提前 ${before.joinToString("、")} 天"
                                        else -> "${before.size} 次"
                                    }
                                )
                            }
                            val nudges = before.map { date.minusDays(it.toLong()) }.filter { it.isAfter(today) }.sorted()
                            if (nudges.isNotEmpty()) NudgeStrip(today, date, nudges)
                        }
                        // 不定钟点的一律只发通知（收在收尾时刻那一组里，§4.6），没得选，这一行不出。
                        // 名字是用户定的（10-11）：标题只叫「轻提醒」，它是什么写在底下两行小字里，开着关着都是这两行。
                        // 第二行不能再长：真机上一行放 20 个字，多了会把最后一两个字单独甩下去（§5.6）
                        if (times.isNotEmpty()) GroupRule()
                        if (times.isNotEmpty()) FormRow(
                            title = "轻提醒",
                            note = "到点只发一条通知，不响铃、不弹全屏。\n响一声、震一下，声音跟通知音量走。",
                            onClick = { ring = if (ring == Ring.NOTICE) Ring.ALARM else Ring.NOTICE; ringTouched = true }
                        ) {
                            PaperSwitch(checked = ring == Ring.NOTICE, onCheckedChange = { ring = if (it) Ring.NOTICE else Ring.ALARM; ringTouched = true })
                        }
                        GroupRule()
                        FormRow(title = "没点完成", onClick = { sheet { showNagSheet = true } }) {
                            Value(
                                when (effectiveNag) {
                                    Nag.NONE -> "不催"
                                    Nag.SOON -> "过一会儿再提"
                                    Nag.DAILY -> "每晚再提"
                                }
                            )
                        }
                        // 不定钟点的一律按当地，这一行对它没意义
                        if (times.isNotEmpty()) {
                            GroupRule()
                            FormRow(
                                title = "跟着我所在时区走",
                                note = if (wallClockAnchored) "飞到哪儿都是当地这个点"
                                else "固定那一瞬间，适合「${Format.humanDay(date)} ${clock(times.first())} 的航班」",
                                onClick = { wallClockAnchored = !wallClockAnchored }
                            ) { PaperSwitch(checked = wallClockAnchored, onCheckedChange = { wallClockAnchored = it }) }
                        }
                    }

                    // ---- 备注 ----
                    GroupLabel("备注")
                    PaperGroup {
                        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
                            if (note.isEmpty()) Text("补充说明，可以不填", style = DaodianType.body, color = colors.hint)
                            BasicTextField(
                                value = note, onValueChange = { note = it },
                                textStyle = DaodianType.body.copy(color = colors.ink),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (existing != null) {
                        // 原话：这条当初是怎么说的。只有模型建的才有（parsedBy 非空）——
                        // 手动建的 rawInput 存的就是标题，摆出来是把标题再念一遍
                        if (existing.parsedBy != null && existing.rawInput.isNotBlank()) {
                            GroupLabel("原话")
                            Text(
                                "「${existing.rawInput}」", style = DaodianType.basis, color = colors.muted,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        // 依据：模型当初怎么算出这个时间的。模型算歪的时候，这是唯一能看出哪步歪了的线索
                        var basis by remember(existing.id) { mutableStateOf<String?>(null) }
                        LaunchedEffect(existing.id) { basis = vm.basisOf(existing.id) }
                        basis?.let {
                            GroupLabel("依据")
                            Text(it, style = DaodianType.basis, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                        // 「不再提醒」（§4.6）：整串停掉、记录留着；「完成」只算这一次。还排着的才有
                        if (existing.status == ReminderStatus.SCHEDULED) {
                            Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "不再提醒", style = DaodianType.bodySmall, color = colors.ink2,
                                    modifier = Modifier
                                        .clickable { vm.stop(existing); onBack() }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(top = if (existing.status == ReminderStatus.SCHEDULED) 0.dp else 28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "删掉这条", style = DaodianType.bodySmall, color = colors.red,
                                modifier = Modifier
                                    .clickable { askDelete = true }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
            UndoBar(
                label = undoItem?.first,
                onUndo = { undoItem?.second?.invoke(); undoItem = null },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // 「记下」钉在底部，键盘弹起时跟着上来。inset 只从这一处来，见 CLAUDE.md「踩过的坑 · 键盘 inset」
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.paper)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp)) {
                SolidButton(if (existing == null) "记下" else "记下改动", onClick = ::save)
            }
        }
    }

    if (showTimeSheet) {
        TimeSheet(
            initial = times,
            dayLabel = "${relativeDay(date)?.let { "$it " } ?: ""}${Format.humanDay(date)}",
            onPick = {
                timesText = timesColumn(it).orEmpty()
                followDefaults(it, repeat)
                showTimeSheet = false
            },
            onDismiss = { showTimeSheet = false }
        )
    }

    if (showNudgeSheet) {
        NudgeSheet(
            day = date, today = today, gap = gap, repeating = repeat != RepeatChoice.NONE, checkTime = checkTime,
            current = before,
            onChange = { beforeText = remindBeforeColumn(it).orEmpty() },
            onDismiss = { showNudgeSheet = false }
        )
    }

    if (showNagSheet) {
        NagSheet(
            current = effectiveNag, dailyAllowed = dailyAllowed, checkTime = checkTime,
            onPick = { nag = it; nagTouched = true; showNagSheet = false },
            onDismiss = { showNagSheet = false }
        )
    }

    if (showRepeatSheet) {
        RepeatSheet(
            options = repeatOptions,
            current = repeat,
            onPick = { choice ->
                repeat = choice
                if (choice != RepeatChoice.NONE) wallClockAnchored = true
                followDefaults(times, choice)
                showRepeatSheet = false
            },
            onDismiss = { showRepeatSheet = false }
        )
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定", color = colors.ink) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消", color = colors.muted) } }
        ) {
            DatePicker(state = state)
        }
    }

    if (askDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("删掉「${existing.title}」？", style = DaodianType.rowTitle, color = colors.ink) },
            text = { Text("闹钟一起撤掉。", style = DaodianType.bodySmall, color = colors.ink2) },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    vm.delete(existing)
                    onBack()
                }) { Text("删掉", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("留着", color = colors.ink2) } },
            containerColor = colors.surface
        )
    }
}

private fun clock(t: LocalTime): String = t.toString().take(5)

/**
 * 标题下那一行：「明天 9月19日 周六 15:00 · 21 小时后」。重复的带一枚徽标；
 * 不定钟点写几点提醒，几个钟点写一天几次
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(date: LocalDate, times: List<LocalTime>, checkTime: LocalTime, repeatLabel: String?) {
    val colors = DaodianColors.current
    val rel = relativeDay(date)
    val whenText = buildString {
        if (rel != null) append(rel).append(' ')
        append(Format.humanDay(date))
        if (times.size == 1) append(' ').append(clock(times[0]))
    }
    val tail = when {
        times.isEmpty() -> "不定钟点，${clock(checkTime)} 提醒"
        times.size > 1 -> "一天 ${times.size} 次"
        repeatLabel != null -> null
        else -> Format.relative(ZonedDateTime.of(date, times[0], ZoneId.systemDefault()).toInstant().toEpochMilli())
    }
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(whenText, style = DaodianType.axisTime, color = colors.ink2)
        if (repeatLabel != null) RepeatBadge(repeatLabel)
        if (tail != null) Text("· $tail", style = DaodianType.caption, color = colors.muted)
    }
}

/**
 * 提前提醒落在哪几天：一根从今天到那天的线，每次提醒一道墨色的刻度，朱砂那根是那天。
 * 设计稿「编辑页」：https://claude.ai/artifact/9DoWbyCPStsXswbFn8GkbG
 */
@Composable
private fun NudgeStrip(today: LocalDate, due: LocalDate, nudges: List<LocalDate>) {
    val colors = DaodianColors.current
    val span = (due.toEpochDay() - today.toEpochDay()).coerceAtLeast(1).toFloat()
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val y = size.height / 2
            val hair = 1.dp.toPx()
            drawLine(colors.rule2, Offset(0f, y), Offset(size.width, y), strokeWidth = hair)
            drawLine(colors.rule2, Offset(hair / 2, y - 4.dp.toPx()), Offset(hair / 2, y + 4.dp.toPx()), strokeWidth = hair)
            nudges.forEach { day ->
                val x = size.width * (day.toEpochDay() - today.toEpochDay()) / span
                drawLine(colors.ink, Offset(x, y - 4.5.dp.toPx()), Offset(x, y + 4.5.dp.toPx()), strokeWidth = hair)
            }
            val end = size.width - 0.75.dp.toPx()
            drawLine(colors.accent, Offset(end, y - 7.5.dp.toPx()), Offset(end, y + 7.5.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("今天", style = DaodianType.settingNote, color = colors.muted)
            Text("下次 ${nudges.first().monthValue}月${nudges.first().dayOfMonth}日", style = DaodianType.settingNote, color = colors.muted)
            Text("${due.monthValue}月${due.dayOfMonth}日", style = DaodianType.settingNote, color = colors.muted)
        }
    }
}

@Composable
private fun FormRow(
    title: String,
    note: String? = null,
    onClick: (() -> Unit)? = null,
    dim: Boolean = false,
    trailing: @Composable RowScope.() -> Unit
) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DaodianType.body, color = if (dim) colors.hint else colors.ink)
            if (note != null) Text(note, style = DaodianType.settingNote, color = if (dim) colors.hint else colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun RowScope.Value(text: String) {
    val colors = DaodianColors.current
    Text(text, style = DaodianType.axisTime, color = colors.ink)
    Spacer(Modifier.width(8.dp))
    ChevronRightIcon(size = 13.dp, tint = colors.muted)
}

/** [time] = null 是不定钟点 */
private data class QuickPick(val label: String, val date: LocalDate, val time: LocalTime?)

/** 新建时的几个快捷钟点。今晚已经过了八点就不给「今晚」 */
private fun quickPicks(now: LocalDateTime = LocalDateTime.now()): List<QuickPick> {
    val today = now.toLocalDate()
    val sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        .let { if (it == today && now.toLocalTime() >= LocalTime.of(10, 0)) it.plusWeeks(1) else it }
    return buildList {
        if (now.toLocalTime() < LocalTime.of(19, 50)) add(QuickPick("今晚 20:00", today, LocalTime.of(20, 0)))
        add(QuickPick("明早 9:00", today.plusDays(1), LocalTime.of(9, 0)))
        add(QuickPick("明天，不定钟点", today.plusDays(1), null))
        add(QuickPick("周六上午", sat, LocalTime.of(10, 0)))
        add(QuickPick("下周一", today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), LocalTime.of(9, 0)))
    }
}

private fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String? =
    when (date.toEpochDay() - today.toEpochDay()) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> null
    }

/** 重复选项，名字按「哪天」写成具体的：每周六、每月 19 号、每年 9月19日 */
private fun repeatOptions(date: LocalDate, originalRrule: String?): List<RepeatOption> {
    val d = date.dayOfMonth
    return buildList {
        add(RepeatOption(RepeatChoice.NONE, "不重复", "就这一次"))
        add(RepeatOption(RepeatChoice.DAILY, "每天", null))
        add(RepeatOption(RepeatChoice.WEEKLY, "每" + Format.weekday(date.dayOfWeek), "按「哪天」那天是周几"))
        add(RepeatOption(RepeatChoice.MONTHLY, "每月 $d 号", if (d > 28) "没有 $d 号的月份顺延到月底" else null))
        add(RepeatOption(RepeatChoice.YEARLY, "每年 ${date.monthValue}月${d}日", null))
        if (originalRrule != null && initialRepeat(originalRrule, date) == RepeatChoice.CUSTOM) {
            add(RepeatOption(RepeatChoice.CUSTOM, Rrule.human(originalRrule) ?: "重复", "原来的规则，原样保留"))
        }
    }
}

/**
 * 这页画得出来的只有五种：不重复、每天、每周（就「哪天」那个周几）、每月（就那个号）、每年。
 * 别的一律算 [RepeatChoice.CUSTOM]，存的时候原样写回去。
 */
private fun initialRepeat(rrule: String?, date: LocalDate): RepeatChoice {
    if (rrule.isNullOrBlank()) return RepeatChoice.NONE
    return when (rrule.removePrefix("RRULE:").uppercase()) {
        "FREQ=DAILY" -> RepeatChoice.DAILY
        "FREQ=WEEKLY;BYDAY=${weekdayCode[date.dayOfWeek.value - 1]}" -> RepeatChoice.WEEKLY
        "FREQ=MONTHLY;BYMONTHDAY=${date.dayOfMonth}" -> RepeatChoice.MONTHLY
        "FREQ=YEARLY" -> RepeatChoice.YEARLY
        else -> RepeatChoice.CUSTOM
    }
}
