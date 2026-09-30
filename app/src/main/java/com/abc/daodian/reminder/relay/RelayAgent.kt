package com.abc.daodian.reminder.relay

import android.content.Context
import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.context.Background
import com.abc.daodian.agent.engine.context.LastTurns
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.memory.MemoryBook
import com.abc.daodian.agent.memory.Recall
import com.abc.daodian.agent.model.ResponsesClient
import com.abc.daodian.agent.model.provider.ApiHealth
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.reminder.application.Reminders
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.reminder.tools.ReminderPrompt
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first

/**
 * 她发来的一句 → 后台一轮模型 → 要你做的事就建成提醒。只带 `create_reminder` 一个工具，问不了你：
 * 拿不准钟点就建成当天事项。建出来的提醒 note 上写「某某派的」，到点的通知上看得见是谁派的。
 */
object RelayAgent {

    sealed interface Outcome {
        data class Created(val ids: List<Long>) : Outcome
        data class NotTask(val reply: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** [task] 是去掉暗号之后的话；[coded] = 她用了暗号，是明确派的 */
    suspend fun run(context: Context, m: RelayMessage, task: String, coded: Boolean): Outcome {
        val app = context.applicationContext
        val profile = ProviderStore.flow(app).first()
        if (!profile.isConfigured) return Outcome.Failed("还没配置模型")

        val tool = CreateReminderTool { plan, ctx ->
            Reminders.commitPlan(app, "${m.who}：${m.text}", plan.copy(note = noteOf(plan.note, m.who)), ctx.model)
        }
        val loop = AgentLoop(ResponsesClient(profile), ToolRegistry(listOf(tool)), SYSTEM, LastTurns(1), maxSteps = 3)
        // 「老地方」「下班」这类，记忆里有就听得懂
        val memory = Background { Recall.preamble(MemoryBook.get(app).all(), null) }

        // 「明天」从她发这句的时刻算。隔太久才收到（扫通知栏扫出来的旧消息）就按现在算，免得建出过去的时间
        val now = System.currentTimeMillis()
        val sentAt = m.at.takeIf { now - it in 0..MAX_AGE_MILLIS } ?: now
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(sentAt), ZoneId.systemDefault())

        val input = buildString {
            append("发消息的人：").append(m.who).append('\n')
            append("原话：").append(task)
            if (coded) append("\n（带了暗号，是她明确派给你的事）")
        }

        val created = mutableListOf<Long>()
        var failure: String? = null
        var rejected: String? = null
        var reply: String? = null
        loop.run(Session(), input, at, background = memory).collect { e ->
            when (e) {
                is AgentEvent.ToolFinished -> {
                    val c = e.outcome.payload as? CreateReminderTool.Created
                    if (c != null) created += c.reminderId else if (!e.outcome.ok) rejected = e.outcome.output
                }
                is AgentEvent.Failed -> { failure = e.reason; ApiHealth.record(e) }
                is AgentEvent.Finished -> {
                    ApiHealth.record(e)
                    reply = e.turn.items.filterIsInstance<Item.AssistantMessage>().lastOrNull()?.text?.trim()
                }
                else -> Unit
            }
        }
        return when {
            created.isNotEmpty() -> Outcome.Created(created)
            failure != null -> Outcome.Failed(failure!!)
            rejected != null -> Outcome.Failed(reply ?: rejected!!)
            else -> Outcome.NotTask(reply?.ifBlank { null } ?: "模型没说话")
        }
    }

    private fun noteOf(note: String?, who: String): String =
        listOfNotNull(note?.takeIf { it.isNotBlank() }, "${who}派的").joinToString(" · ")

    private const val MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L

    private val SYSTEM = """
你在提醒 app「到点」里，替用户接别人在聊天软件里派给他的事。每次输入是某个人发给用户的一句话，
开头方括号是她发这句话的时刻、星期和时区，比如 [2026-09-18 21:03 周五 +08:00]。
「明天」「下周三」一律从这个时刻推算。「下周X」指下一个自然周的那天，不是往后数 7 天。

## 要不要建
- 这句话是要用户去做一件事（买、带、取、交、打电话、别忘了……），就调 create_reminder 建提醒。
  一句话里有几件时间不同的事，就建几条。
- 不是要他做事（闲聊、撒娇、问他在干嘛、说她自己的安排、表情），就不调工具，只回一句「不是待办：」加一句理由。
- 输入里写了「带了暗号」的，是她明确派的事：除非完全读不出要做什么，都要建。

## 怎么建
- 你问不了用户，也不用等他点头。
- 标题写用户要做的动作，站在用户这边：「你下班帮我取个快递」→「取快递」。
- 原话里的「我」是发消息的她，「你」是用户：「明天提醒我吃药」→ 标题「提醒她吃药」，用输入里她的名字替掉「她」。
- note 只写她给的补充（地点、要带的东西），没有就 null。app 会自己写上是谁派的。
- 钟点有歧义（只说了「下午」「晚点」「下班」）就建成当天事项（allDay），不要猜钟点。
- 工具说没建（比如时间已经过了），不要换个时间重试，回一句「没建：」加原因。

${ReminderPrompt.RULES}
""".trimIndent()
}
