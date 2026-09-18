package com.abc.daodian.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.abc.daodian.data.dueDate
import com.abc.daodian.data.isAllDay
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.ChevronRightIcon
import com.abc.daodian.ui.common.Format
import com.abc.daodian.ui.common.LedgerGroup
import com.abc.daodian.ui.common.LedgerLabel
import com.abc.daodian.ui.common.LedgerRule
import com.abc.daodian.ui.common.RepeatBadge
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
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
private val weekdayName = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 手动建 / 改一条提醒 —— 逃生舱。必须能完全脱离 AI 建成一条完整提醒。见 DESIGN.md §05
 *
 * 版式跟设置页同一本账（设计稿方向 A：https://claude.ai/artifact/UdcBGTTx5quxPfsnR5Akq7）：
 * 宋体标题写在横线上，底下一行人话复述「什么时候」，再往下时间 / 重复 / 备注三组纸，
 * 「记下」钉在底部。几点和重复各拉一张底纸，不用 Material 的表盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditReminderScreen(vm: MainViewModel, reminderId: Long?, onBack: () -> Unit) {
    val colors = DaodianColors.current
    val reminders by vm.reminders.collectAsState()
    val existing = remember(reminders, reminderId) { reminders.firstOrNull { it.id == reminderId } }

    var title by rememberSaveable(existing) { mutableStateOf(existing?.title ?: "") }
    var note by rememberSaveable(existing) { mutableStateOf(existing?.note ?: "") }

    val zone = ZoneId.systemDefault()
    val defaultDateTime = remember { ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0) }
    // 当天事项的日期是它「算哪天的」（顺延过的也还是原来那天），不是闹钟挂在哪天
    var date by rememberSaveable(existing) {
        mutableStateOf(
            existing?.let { it.dueDate() ?: Instant.ofEpochMilli(it.nextTriggerAt).atZone(zone).toLocalDate() }
                ?: defaultDateTime.toLocalDate()
        )
    }
    var allDay by rememberSaveable(existing) { mutableStateOf(existing?.isAllDay ?: false) }
    val checkTime by vm.dayCheckTime.collectAsState()
    var time by rememberSaveable(existing) {
        mutableStateOf(
            existing?.let { Instant.ofEpochMilli(it.nextTriggerAt).atZone(zone).toLocalTime() }
                ?: defaultDateTime.toLocalTime()
        )
    }
    val originalRrule = existing?.rrule
    var repeat by rememberSaveable(existing) { mutableStateOf(initialRepeat(originalRrule, date)) }
    var wallClockAnchored by rememberSaveable(existing) {
        mutableStateOf(existing?.wallClockAnchored ?: false)
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimeSheet by remember { mutableStateOf(false) }
    var showRepeatSheet by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    fun buildRrule(): String? = when (repeat) {
        RepeatChoice.NONE -> null
        RepeatChoice.DAILY -> "FREQ=DAILY"
        RepeatChoice.WEEKLY -> "FREQ=WEEKLY;BYDAY=${weekdayCode[date.dayOfWeek.value - 1]}"
        RepeatChoice.MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=${date.dayOfMonth}"
        RepeatChoice.YEARLY -> "FREQ=YEARLY"
        RepeatChoice.CUSTOM -> originalRrule
    }

    fun save() {
        val triggerAt = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
        vm.upsertManual(
            id = existing?.id,
            title = title.ifBlank { "未命名提醒" },
            note = note.ifBlank { null },
            triggerAt = triggerAt,
            rrule = buildRrule(),
            wallClockAnchored = wallClockAnchored,
            dueDay = if (allDay) date else null
        )
        onBack()
    }

    val repeatOptions = repeatOptions(date, originalRrule)
    val repeatLabel = repeatOptions.firstOrNull { it.choice == repeat }?.label ?: "不重复"

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = if (existing == null) "新建提醒" else "改一下", onBack = onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

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
                Summary(date = date, time = time, allDay = allDay, checkTime = checkTime, repeatLabel = repeatLabel.takeIf { repeat != RepeatChoice.NONE })
            }

            Column(Modifier.padding(horizontal = 20.dp)) {

                // 快捷钟点只在新建时给 —— 改一条已有的，多半是改一点点，不是整个换掉
                if (existing == null) {
                    Row(
                        Modifier.padding(top = 18.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickPicks().forEach { q ->
                            Chip(q.label, selected = !allDay && date == q.date && time == q.time) {
                                allDay = false; date = q.date; time = q.time
                            }
                        }
                    }
                }

                // ---- 时间 ----
                LedgerLabel("时间")
                LedgerGroup {
                    Segmented(
                        left = "定个钟点", right = "当天之内", rightOn = allDay,
                        onPick = { allDay = it },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    FormRow(
                        title = if (repeat == RepeatChoice.NONE) "哪天" else "从哪天起",
                        onClick = { showDatePicker = true }
                    ) { Value("${relativeDay(date)?.let { "$it · " } ?: ""}${Format.humanDay(date)}") }
                    LedgerRule()
                    if (allDay) {
                        FormRow(
                            title = "几点",
                            note = "不定钟点。那天晚上 ${checkTime.toString().take(5)} 提醒一次，没做完顺延到第二天。"
                        ) {}
                    } else {
                        FormRow(title = "几点", onClick = { showTimeSheet = true }) {
                            Text(time.toString().take(5), style = DaodianType.settingValue, color = colors.ink)
                            Spacer(Modifier.width(8.dp))
                            ChevronRightIcon(size = 13.dp, tint = colors.muted)
                        }
                    }
                }

                // ---- 重复 ----
                LedgerLabel("重复")
                LedgerGroup {
                    FormRow(title = "重复", onClick = { showRepeatSheet = true }) { Value(repeatLabel) }
                    // 当天事项没有钟点，「跟着时区走」对它没意义（一律按当地晚上提醒）
                    if (!allDay) {
                        LedgerRule()
                        FormRow(
                            title = "跟着我所在时区走",
                            note = if (wallClockAnchored) "飞到哪儿都是当地这个点，适合「每天早上 8 点吃药」"
                            else "固定那一瞬间，适合「${Format.humanDay(date)} ${time.toString().take(5)} 的会」",
                            onClick = { wallClockAnchored = !wallClockAnchored }
                        ) {
                            Switch(
                                checked = wallClockAnchored,
                                onCheckedChange = { wallClockAnchored = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = colors.solid,
                                    checkedThumbColor = colors.onSolid,
                                    checkedBorderColor = colors.solid,
                                    uncheckedTrackColor = colors.surfaceAlt,
                                    uncheckedThumbColor = colors.rule2,
                                    uncheckedBorderColor = colors.rule2
                                )
                            )
                        }
                    }
                }

                // ---- 备注 ----
                LedgerLabel("备注")
                LedgerGroup {
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
                        LedgerLabel("原话")
                        Text(
                            "「${existing.rawInput}」", style = DaodianType.basis, color = colors.muted,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
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

        // 「记下」钉在底部，键盘弹起时跟着上来。inset 只从这一处来，见 CLAUDE.md「键盘 inset 踩过一次」
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
            initial = time,
            dayLabel = "${relativeDay(date)?.let { "$it " } ?: ""}${Format.humanDay(date)}",
            onPick = { time = it; showTimeSheet = false },
            onDismiss = { showTimeSheet = false }
        )
    }

    if (showRepeatSheet) {
        RepeatSheet(
            options = repeatOptions,
            current = repeat,
            onPick = { choice ->
                repeat = choice
                if (choice != RepeatChoice.NONE) wallClockAnchored = true
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

/** 标题下那一行：「明天 9月19日 周六 15:00 · 21 小时后」，重复的带一枚徽标 */
@Composable
private fun Summary(date: LocalDate, time: LocalTime, allDay: Boolean, checkTime: LocalTime, repeatLabel: String?) {
    val colors = DaodianColors.current
    val rel = relativeDay(date)
    val whenText = buildString {
        if (rel != null) append(rel).append(' ')
        append(Format.humanDay(date))
        if (!allDay) append(' ').append(time.toString().take(5))
    }
    val tail = if (allDay) "当天之内 · 晚上 ${checkTime.toString().take(5)} 提醒"
    else Format.relative(ZonedDateTime.of(date, time, ZoneId.systemDefault()).toInstant().toEpochMilli())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(whenText, style = DaodianType.axisTime, color = colors.ink2)
        if (repeatLabel != null) RepeatBadge(repeatLabel)
        else Text("· $tail", style = DaodianType.caption, color = colors.muted)
    }
}

@Composable
private fun FormRow(
    title: String,
    note: String? = null,
    onClick: (() -> Unit)? = null,
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
            Text(title, style = DaodianType.body, color = colors.ink)
            if (note != null) Text(note, style = DaodianType.settingNote, color = colors.muted)
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

/** 两段开关「定个钟点 / 当天之内」，选中那段墨色实心 */
@Composable
private fun Segmented(left: String, right: String, rightOn: Boolean, onPick: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, colors.rule2, RoundedCornerShape(22.dp))
            .padding(3.dp)
    ) {
        listOf(left to false, right to true).forEach { (label, isRight) ->
            val on = isRight == rightOn
            Box(
                Modifier
                    .weight(1f)
                    .then(if (on) Modifier.background(colors.solid, RoundedCornerShape(19.dp)) else Modifier)
                    .clickable { onPick(isRight) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = DaodianType.bodySmall, color = if (on) colors.onSolid else colors.ink2)
            }
        }
    }
}

private data class QuickPick(val label: String, val date: LocalDate, val time: LocalTime)

/** 新建时的几个快捷钟点。今晚已经过了八点就不给「今晚」 */
private fun quickPicks(now: LocalDateTime = LocalDateTime.now()): List<QuickPick> {
    val today = now.toLocalDate()
    val sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        .let { if (it == today && now.toLocalTime() >= LocalTime.of(10, 0)) it.plusWeeks(1) else it }
    return buildList {
        if (now.toLocalTime() < LocalTime.of(19, 50)) add(QuickPick("今晚 20:00", today, LocalTime.of(20, 0)))
        add(QuickPick("明早 9:00", today.plusDays(1), LocalTime.of(9, 0)))
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
        add(RepeatOption(RepeatChoice.WEEKLY, "每" + weekdayName[date.dayOfWeek.value - 1], "按「哪天」那天是周几"))
        add(RepeatOption(RepeatChoice.MONTHLY, "每月 $d 号", if (d > 28) "没有 $d 号的月份顺延到月底" else null))
        add(RepeatOption(RepeatChoice.YEARLY, "每年 ${date.monthValue}月${d}日", null))
        if (originalRrule != null && initialRepeat(originalRrule, date) == RepeatChoice.CUSTOM) {
            add(RepeatOption(RepeatChoice.CUSTOM, Format.humanRrule(originalRrule) ?: "重复", "原来的规则，原样保留"))
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
