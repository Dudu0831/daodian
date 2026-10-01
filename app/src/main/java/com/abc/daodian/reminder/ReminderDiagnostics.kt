package com.abc.daodian.reminder

import android.content.Context
import com.abc.daodian.agent.diagnostics.Diagnostics.time
import com.abc.daodian.agent.diagnostics.rows
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.relay.RelayDatabase

/** 「导出诊断」里提醒那一段：排了几条、下一次什么时候、最近响得准不准、派活收到的。不写标题、不写原话 */
internal object ReminderDiagnostics {

    fun build(context: Context): String = buildString {
        val db = ReminderDatabase.get(context).openHelper.readableDatabase
        val now = System.currentTimeMillis()

        appendLine("按状态：" + db.rows("SELECT status, COUNT(*) FROM reminders GROUP BY status")
            .joinToString("、") { "${it[0]} ${it[1]}" }.ifEmpty { "一条没有" })

        val due = db.rows(
            "SELECT id, nextTriggerAt, rrule IS NOT NULL, dueDay IS NOT NULL FROM reminders " +
                "WHERE status = 'SCHEDULED' ORDER BY nextTriggerAt LIMIT 30"
        )
        appendLine("排着的（最近 30 条，#id · 下次 · 重复 / 当天事项；过点没响的打 !）：")
        due.forEach { (id, next, repeat, day) ->
            val at = next?.toLongOrNull()
            val late = at != null && at < now - 60_000
            appendLine("  ${if (late) "!" else " "} #$id · ${time(at)}" +
                (if (repeat == "1") " · 重复" else "") + (if (day == "1") " · 当天" else ""))
        }

        appendLine("最近响的（30 次，#提醒 · 该响 · 实际 · 晚了多少 · 来路）：")
        db.rows("SELECT reminderId, scheduledAt, firedAt, source FROM fire_log ORDER BY id DESC LIMIT 30").forEach { (id, sch, fired, src) ->
            val drift = (fired?.toLongOrNull() ?: 0) - (sch?.toLongOrNull() ?: 0)
            appendLine("  #$id · ${time(sch?.toLongOrNull())} · ${time(fired?.toLongOrNull())} · ${drift}ms · $src")
        }

        val relay = RelayDatabase.get(context).openHelper.readableDatabase
        val got = relay.rows("SELECT pkg, at, receivedAt, how, status FROM relay_message ORDER BY id DESC LIMIT 20")
        if (got.isNotEmpty()) {
            appendLine("派活收到的（20 条，app · 发来 · 收到 · 怎么收的 · 结果）：")
            got.forEach { (pkg, at, recv, how, status) ->
                appendLine("  $pkg · ${time(at?.toLongOrNull())} · ${time(recv?.toLongOrNull())} · $how · $status")
            }
        }
    }
}
