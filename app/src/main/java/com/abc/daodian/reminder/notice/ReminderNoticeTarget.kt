package com.abc.daodian.reminder.notice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.abc.daodian.intake.plugin.Handed
import com.abc.daodian.intake.plugin.NoticeReports
import com.abc.daodian.intake.plugin.NoticeTarget
import com.abc.daodian.intake.plugin.Receipt
import com.abc.daodian.reminder.ReminderRoutes
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.data.Reminder
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.reminder.data.dueDate
import com.abc.daodian.reminder.domain.ReminderText
import com.abc.daodian.reminder.domain.Rrule
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.runtime.Input
import com.abc.daodian.runtime.RunResult
import com.abc.daodian.runtime.payloads
import com.abc.daodian.runtime.reply
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.navigation.Launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 提醒接通知管家交来的活（DESIGN.md §12.5）：一条一条交给提醒子代理（[ReminderNoticeAgent]），建成了弹一条通知告诉你，
 * 点开是那条提醒。将来取代派活（`relay/Relay`，第 8 步）。
 *
 * 重交防重复：办之前先问收件箱这次交接有没有回执，有就不再办；建成了马上写回执，不等交接那边。
 * 进程恰好死在「建好」和「写回执」之间会多建一条 —— 接受（NOTICE_PLAN 第 10 节第 5 条）。
 * 模型没配、跑失败的不回执，交接那边过一会儿再交。
 */
object ReminderNoticeTarget : NoticeTarget {

    /** 和 `ReminderModule.ID` 一样。交接表里存它，不改 */
    const val ID = "reminder"

    override val id = ID

    override val label = "提醒"

    override val brief = "要用户去做的事，建成提醒。附言写清：几点（写钟点，不写「下班」）、标题怎么写、要不要轻提醒（规则里明说了才写）"

    /** 一条一条办，别两条同时叫模型 */
    private val lock = Mutex()

    override suspend fun accept(context: Context, handed: List<Handed>): List<Receipt> = lock.withLock {
        val app = context.applicationContext
        handed.mapNotNull { h ->
            NoticeReports.receiptOf(app, h.handoffId)?.let { return@mapNotNull Receipt(h.handoffId, it) }
            val receipt = when (val o = runCatching { ask(app, h) }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }) {
                is Outcome.Created -> {
                    val reminders = o.ids.mapNotNull { ReminderDatabase.get(app).reminderDao().byId(it) }
                    val said = reminders.joinToString("；") { "${whenOf(it)} · ${it.title}" }
                    val route = ReminderRoutes.edit(o.ids.first())
                    notify(app, h, "建了一条提醒", said, Launch.intent(app, route = route))
                    Receipt(h.handoffId, said, route)
                }
                is Outcome.NotTask -> Receipt(h.handoffId, o.reply)
                is Outcome.Failed -> null
            }
            receipt?.also { NoticeReports.report(app, it.handoffId, it.text, it.route) }
        }
    }

    private sealed interface Outcome {
        data class Created(val ids: List<Long>) : Outcome
        data class NotTask(val reply: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    private suspend fun ask(app: Context, h: Handed): Outcome {
        val source = sourceOf(h)
        val agent = ReminderNoticeAgent.build { plan, ctx ->
            Reminders.commitPlan(app, "$source：${h.body.orEmpty()}", plan.copy(note = noteOf(plan.note, source)), ctx.model)
        }

        // 「明天」从通知的时刻算。隔太久才交到（扫通知栏扫出来的旧通知、模型挂了一阵）就按现在算，免得建出过去的时间
        val now = System.currentTimeMillis()
        val sentAt = h.notice.at.takeIf { now - it in 0..MAX_AGE_MILLIS } ?: now
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(sentAt), ZoneId.systemDefault())

        // 「老地方」「下班」这类，记忆里有就听得懂
        val input = ReminderNoticePrompt.input(h.app, h.title, h.body, h.notice.redacted, h.note)
        val result = AgentRuntime.runOnce(agent, Input(input, at), AgentRuntime.memory)
        val created = result.payloads<CreateReminderTool.Created>().map { it.reminderId }
        return when {
            created.isNotEmpty() -> Outcome.Created(created)
            result == RunResult.NotConfigured -> Outcome.Failed("还没配置模型")
            result is RunResult.Failed -> Outcome.Failed(result.reason)
            else -> Outcome.NotTask(result.reply?.ifBlank { null } ?: "模型没说话")
        }
    }

    /** 「老婆（微信）」「菜鸟驿站（短信）」 */
    private fun sourceOf(h: Handed): String = h.title?.let { "$it（${h.app}）" } ?: h.app

    private fun noteOf(note: String?, source: String): String =
        listOfNotNull(note?.takeIf { it.isNotBlank() }, "来自$source").joinToString(" · ")

    /** 通知比这还旧，「明天」就按现在算 */
    private const val MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L

    /** 「10月1日 周四 15:00」「今天之内」「每天 08:00」 */
    private fun whenOf(r: Reminder): String {
        val repeat = Rrule.human(r.rrule)
        val day = r.dueDate()
        return when {
            day != null && repeat != null -> "$repeat · 当天之内"
            day != null -> ReminderText.dayTaskWhen(day)
            repeat != null -> "$repeat ${Format.clock(r.nextTriggerAt)}"
            else -> Format.humanDateTime(r.nextTriggerAt)
        }
    }

    private fun notify(context: Context, h: Handed, title: String, text: String, open: Intent) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        // 渠道还是派活那个：系统按 id 记你对它的设置。名字第 8 步删派活时再改
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "别人派的事", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "名单上的人在微信里派了事、建成了提醒时告诉你"
            }
        )
        val id = (NOTIFY_BASE + h.handoffId % NOTIFY_SPAN).toInt()
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val quote = listOfNotNull(h.title, h.body).joinToString("：")
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (quote.isEmpty()) text else "$text\n「$quote」"))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(id, n) }
    }

    private const val CHANNEL_ID = "relay_v1"

    /** 和派活的 700_000 起错开 */
    private const val NOTIFY_BASE = 800_000L
    private const val NOTIFY_SPAN = 100_000L
}
