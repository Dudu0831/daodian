package com.abc.daodian.ledger.organize

import android.content.Context
import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.agent.engine.context.LastTurns
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.model.ResponsesClient
import com.abc.daodian.agent.model.provider.ApiHealth
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.ledger.capture.PaySources
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.data.db.AgentRun
import com.abc.daodian.ledger.data.db.RawNotification
import com.abc.daodian.ledger.domain.ExpenseQuery
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.tools.LedgerPrompt
import com.abc.daodian.ledger.tools.LedgerTools
import com.abc.daodian.ledger.tools.RecordExpensesTool
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 整理：把待整理的原始通知交给整理 agent（LEDGER_PLAN.md §4 ②）。
 *
 * 代码先数，0 条就睡、不花一分钱；有才叫模型。整轮拿着账本锁（[LedgerTools.LOCK]）——
 * 同一时刻只有一个 agent 写账；别人正拿着就这次不跑，等下一轮。跑的时候挂在 [AgentActivity] 上，
 * 顶栏那枚印看得到。
 *
 * 模型在后台自己决定一切（放开模式，不问）：写进去的都是「自动归的」或「待确认」，
 * 事后都能在对话里改；用户确认过的它动不了（工具层挡着）。
 */
object Organizer {

    /** 最近这么久内到的先不整理：招行 / 掌生两条别被拆进两批，遮蔽版有时间补回真正文（§8.3） */
    const val COOLDOWN_MILLIS = 10 * 60 * 1000L

    /** 一批最多几条；攒多了分几批，上一批的结果会出现在下一批的「最近流水」里（§8.6） */
    const val BATCH = 30
    private const val MAX_BATCHES = 6

    sealed interface Result {
        /** 没有待整理的，没叫模型 */
        data object Idle : Result
        /** 别的 agent 正拿着账本锁 */
        data object Busy : Result
        data class Skipped(val why: String) : Result
        data class Done(val run: AgentRun) : Result
    }

    /**
     * @param reason 谁叫的：periodic / manual / check。写进运行记录
     * @param respectCooldown 手动点「现在整理」、对账前的强制整理传 false：连刚到的也一起整
     */
    suspend fun run(context: Context, reason: String, respectCooldown: Boolean = true): Result {
        val app = context.applicationContext
        return AgentActivity.tryRun(LedgerTools.LOCK, "organize", "整理账目") { organize(app, reason, respectCooldown) }
            ?: Result.Busy
    }

    private suspend fun organize(context: Context, reason: String, respectCooldown: Boolean): Result {
        val store = LedgerStore.get(context)
        val dao = store.dao

        // 先让采集器把通知栏扫一遍：丢了的实时回调、遮蔽后还没补回来的，趁这时候捞一把
        PaySources.sweep(context)
        delay(1500)

        val before = System.currentTimeMillis() - if (respectCooldown) COOLDOWN_MILLIS else 0
        if (dao.countPendingRaws(before) == 0) return Result.Idle

        val profile = ProviderStore.flow(context).first()
        if (!profile.isConfigured) return Result.Skipped("还没配置模型")

        val parsedBy = "${profile.model}@${LedgerPrompt.VERSION}"
        val tools = ToolRegistry(LedgerTools.forOrganizer(store, { parsedBy }))
        val loop = AgentLoop(ResponsesClient(profile), tools, LedgerPrompt.ORGANIZE, context = LastTurns(1), maxSteps = 5)

        var run = AgentRun(kind = "organize", reason = reason, startedAt = System.currentTimeMillis(), model = profile.model)
        run = run.copy(id = dao.insertRun(run))
        var seen = emptySet<Long>()
        try {
            for (i in 0 until MAX_BATCHES) {
                val batch = dao.pendingRaws(before, BATCH)
                // 上一批交完了还剩同样几条：模型处理不了它们，别原地打转，留给下次
                if (batch.isEmpty() || batch.map { it.id }.toSet() == seen) break
                seen = batch.map { it.id }.toSet()

                var failure: String? = null
                loop.run(Session(), inputOf(store, batch), ZonedDateTime.now()).collect { e ->
                    when (e) {
                        is AgentEvent.ToolFinished -> (e.outcome.payload as? RecordExpensesTool.Recorded)?.let { r ->
                            run = run.copy(recorded = run.recorded + r.txnIds.size, ignored = run.ignored + r.ignored + r.unreadable)
                        }
                        is AgentEvent.Failed -> { failure = e.reason; ApiHealth.record(e) }
                        is AgentEvent.Finished -> ApiHealth.record(e)
                        else -> Unit
                    }
                }
                run = run.copy(rawCount = run.rawCount + batch.size)
                failure?.let { run = run.copy(error = it) }
                if (failure != null) break
            }
        } catch (t: Throwable) {
            run = run.copy(error = t.message ?: t.javaClass.simpleName)
            if (t is CancellationException) throw t
        } finally {
            run = run.copy(finishedAt = System.currentTimeMillis())
            withContext(NonCancellable) { dao.updateRun(run) }
        }
        return Result.Done(run)
    }

    /** 这一轮喂给整理 agent 的全部背景。会变的都在这里，system 提示词保持不动 */
    private suspend fun inputOf(store: LedgerStore, batch: List<RawNotification>): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val recent = store.query(ExpenseQuery(fromDay = LedgerDays.dayInt(today.minusDays(3)), limit = 80)).reversed()
        val memory = store.merchantMemory()
        return buildString {
            append("这一批原始通知（${batch.size} 条）：\n")
            batch.forEach { append(LedgerText.raw(store.noteOf(it), zone)).append('\n') }
            append("\n").append(LedgerText.categoryTree(store.categories())).append('\n')
            append("\n商户记忆（用户确认过的）：")
            append(if (memory.isEmpty()) "还没有" else memory.joinToString("；") { (m, c) -> "$m → $c" })
            append("\n\n最近几天已有的流水：")
            if (recent.isEmpty()) append("没有") else recent.forEach { append('\n').append(LedgerText.txn(it, zone)) }
        }
    }
}
