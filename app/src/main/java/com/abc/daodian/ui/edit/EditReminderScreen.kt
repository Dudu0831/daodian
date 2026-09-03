package com.abc.daodian.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import com.abc.daodian.ui.MainViewModel
import com.abc.daodian.ui.common.ScreenTopBar
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private enum class RepeatChoice(val label: String) {
    NONE("不重复"), DAILY("每天"), WEEKLY("每周"), MONTHLY("每月"), YEARLY("每年")
}

private val weekdayCode = arrayOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/**
 * 手动建 / 改一条提醒 —— 逃生舱。必须能完全脱离 AI 建成一条完整提醒。见 DESIGN.md §05
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
    var date by rememberSaveable(existing) {
        mutableStateOf(
            existing?.let { Instant.ofEpochMilli(it.nextTriggerAt).atZone(zone).toLocalDate() }
                ?: defaultDateTime.toLocalDate()
        )
    }
    var time by rememberSaveable(existing) {
        mutableStateOf(
            existing?.let { Instant.ofEpochMilli(it.nextTriggerAt).atZone(zone).toLocalTime() }
                ?: defaultDateTime.toLocalTime()
        )
    }
    var repeat by rememberSaveable(existing) {
        mutableStateOf(initialRepeat(existing?.rrule))
    }
    var wallClockAnchored by rememberSaveable(existing) {
        mutableStateOf(existing?.wallClockAnchored ?: false)
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    fun buildRrule(): String? = when (repeat) {
        RepeatChoice.NONE -> null
        RepeatChoice.DAILY -> "FREQ=DAILY"
        RepeatChoice.WEEKLY -> "FREQ=WEEKLY;BYDAY=${weekdayCode[date.dayOfWeek.value - 1]}"
        RepeatChoice.MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=${date.dayOfMonth}"
        RepeatChoice.YEARLY -> "FREQ=YEARLY"
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar(title = if (existing == null) "新建提醒" else "改一下", onBack = onBack) {
            TextButton(onClick = {
                val triggerAt = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
                vm.upsertManual(
                    id = existing?.id,
                    title = title.ifBlank { "未命名提醒" },
                    note = note.ifBlank { null },
                    triggerAt = triggerAt,
                    rrule = buildRrule(),
                    wallClockAnchored = wallClockAnchored
                )
                onBack()
            }) { Text("保存", color = colors.green) }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        ) {
            FieldLabel("标题")
            PlainField(title, { title = it }, "要提醒什么")
            Spacer(Modifier.height(20.dp))

            FieldLabel("备注")
            PlainField(note, { note = it }, "补充说明，可以不填")
            Spacer(Modifier.height(28.dp))

            FieldLabel("时间")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChip(date.toString(), selected = false, onClick = { showDatePicker = true })
                ChoiceChip(time.toString().take(5), selected = false, onClick = { showTimePicker = true })
            }
            Spacer(Modifier.height(28.dp))

            FieldLabel("重复")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatChoice.entries.forEach { choice ->
                    ChoiceChip(choice.label, selected = repeat == choice, onClick = {
                        repeat = choice
                        if (choice != RepeatChoice.NONE) wallClockAnchored = true
                    })
                }
            }
            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("跟着我所在时区走", style = DaodianType.bodySmall, color = colors.ink)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (wallClockAnchored) "飞到哪儿都是当地这个点，适合「每天早上 8 点吃药」"
                        else "固定那一瞬间，适合「9月2号15:00的会」",
                        style = DaodianType.caption, color = colors.muted
                    )
                }
                Switch(
                    checked = wallClockAnchored,
                    onCheckedChange = { wallClockAnchored = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.green)
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            androidx.compose.material3.DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(state.hour, state.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }
}

private fun initialRepeat(rrule: String?): RepeatChoice {
    if (rrule.isNullOrBlank()) return RepeatChoice.NONE
    return when {
        rrule.contains("FREQ=DAILY") -> RepeatChoice.DAILY
        rrule.contains("FREQ=WEEKLY") -> RepeatChoice.WEEKLY
        rrule.contains("FREQ=MONTHLY") -> RepeatChoice.MONTHLY
        rrule.contains("FREQ=YEARLY") -> RepeatChoice.YEARLY
        else -> RepeatChoice.NONE
    }
}

@Composable
private fun FieldLabel(text: String) {
    val colors = DaodianColors.current
    Text(text, style = DaodianType.monoSmall, color = colors.muted, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun PlainField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val colors = DaodianColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(4.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(4.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, style = DaodianType.body, color = colors.muted)
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = DaodianType.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.green)
        )
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DaodianColors.current
    Box(
        Modifier
            .let { if (selected) it.background(colors.green, RoundedCornerShape(100.dp)) else it }
            .let { if (!selected) it.border(1.dp, colors.rule2, RoundedCornerShape(100.dp)) else it }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(label, style = DaodianType.bodySmall, color = if (selected) colors.onGreen else colors.ink2)
    }
}
