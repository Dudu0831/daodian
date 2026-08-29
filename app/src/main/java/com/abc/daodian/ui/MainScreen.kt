package com.abc.daodian.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abc.daodian.data.FireLog
import com.abc.daodian.data.FireSource
import com.abc.daodian.data.Reminder
import com.abc.daodian.data.ReminderStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M1 的临时界面 —— 丑，但足够驱动放置测试。
 * 等原型设计到位后整个 ui/ 包会被替换掉，内核（data/ + schedule/）不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("提醒", "投递日志", "体检")

    Scaffold(
        topBar = { TopAppBar(title = { Text("到点 · M1") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> RemindersTab(vm)
                1 -> LogTab(vm)
                2 -> HealthTab()
            }
        }
    }
}

private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private fun Long.fmt(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(fmt)

@Composable
private fun RemindersTab(vm: MainViewModel) {
    val reminders by vm.reminders.collectAsState()
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("1") }

    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("提醒内容") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit) },
                label = { Text("几分钟后") },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val m = minutes.toLongOrNull() ?: 1
                    vm.addIn(title.ifBlank { "未命名提醒" }, m)
                    title = ""
                }
            ) { Text("加一条") }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { vm.startSoakTest() }, modifier = Modifier.weight(1f)) {
                Text("放置测试 20 条 / 48h", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.rescheduleAll() }) { Text("重排", fontSize = 12.sp) }
        }

        HorizontalDivider()
        Text(
            "共 ${reminders.size} 条 · 「已排」直接问 AlarmManager，不看数据库",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(reminders, key = { it.id }) { r -> ReminderRow(r, vm) }
        }
    }
}

@Composable
private fun ReminderRow(r: Reminder, vm: MainViewModel) {
    val armed = remember(r.id, r.nextTriggerAt, r.status) { vm.isArmed(r.id) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(r.title, fontWeight = FontWeight.Bold)
            Text(
                "${r.nextTriggerAt.fmt()} · ${r.status}" +
                    (if (r.rrule != null) " · ${r.rrule}" else "") +
                    (if (r.status == ReminderStatus.SCHEDULED) (if (armed) " · 已排" else " · ⚠ 没排上") else ""),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (r.status == ReminderStatus.SCHEDULED && !armed) Color(0xFFB3261E)
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.markDone(r) }) { Text("完成", fontSize = 12.sp) }
                TextButton(onClick = { vm.delete(r) }) { Text("删除", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun LogTab(vm: MainViewModel) {
    val logs by vm.logs.collectAsState()
    val nonAlarm by vm.nonAlarmCount.collectAsState()

    Column(Modifier.padding(16.dp)) {
        val drifts = logs.map { it.driftMillis }
        Text(
            buildString {
                append("${logs.size} 条投递记录")
                if (drifts.isNotEmpty()) {
                    append(" · 最大漂移 ${drifts.max() / 1000}s")
                    append(" · 中位 ${drifts.sorted()[drifts.size / 2] / 1000}s")
                }
            },
            fontWeight = FontWeight.Bold
        )
        Text(
            if (nonAlarm == 0) "全部走主闹钟路径 ✓"
            else "⚠ $nonAlarm 条走了兜底补发 —— 主闹钟路径正在被掐，回去检查 §9.2 的保活配置",
            fontSize = 12.sp,
            color = if (nonAlarm == 0) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFB3261E),
            modifier = Modifier.padding(vertical = 6.dp)
        )
        TextButton(onClick = { vm.clearLogs() }) { Text("清空日志", fontSize = 12.sp) }
        HorizontalDivider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(logs, key = { it.id }) { log -> LogRow(log) }
        }
    }
}

@Composable
private fun LogRow(log: FireLog) {
    val drift = log.driftMillis
    val bad = drift > 30_000 || log.source != FireSource.ALARM
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(log.title, fontSize = 13.sp)
        Text(
            "应响 ${log.scheduledAt.fmt()} → 实响 ${log.firedAt.fmt()} · 漂移 ${if (drift >= 0) "+" else ""}${drift / 1000}s · ${log.source}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (bad) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HealthTab() {
    val context = LocalContext.current
    val items = remember { HealthCheck.run(context) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${if (item.ok) "✓" else "✗"}  ${item.label}",
                        fontWeight = FontWeight.Bold,
                        color = if (item.ok) MaterialTheme.colorScheme.onSurface else Color(0xFFB3261E)
                    )
                    Text(item.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!item.ok && item.fixIntent != null) {
                        TextButton(onClick = { context.safeStart(item.fixIntent) }) {
                            Text("去设置", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Text(
            "MagicOS 的「应用启动管理」没有公开 API 可以检测，只能手动设 —— " +
                "设置 → 应用启动管理 → 到点 → 关掉自动管理 → 三个开关全开。见设计文档 §9.2",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun Context.safeStart(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
