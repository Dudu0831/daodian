package com.abc.daodian.ledger.organize

import android.content.Context
import com.abc.daodian.BuildConfig
import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.agent.engine.context.LastTurns
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.memory.Memory
import com.abc.daodian.agent.memory.MemoryBook
import com.abc.daodian.agent.model.ResponsesClient
import com.abc.daodian.agent.model.provider.ApiHealth
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.intake.Intake
import com.abc.daodian.ledger.data.LedgerSettings
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.data.db.AgentRun
import com.abc.daodian.ledger.data.db.RawNotification
import com.abc.daodian.ledger.domain.ExpenseQuery
import com.abc.daodian.ledger.domain.LedgerDays
import com.abc.daodian.ledger.domain.LedgerTags
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.tools.LedgerPrompt
import com.abc.daodian.ledger.tools.LedgerTools
import com.abc.daodian.ledger.tools.RecordExpensesTool
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 整理：把待整理的原始通知交给整理 agent（DESIGN.md §10.1 ②）。
 *
 * 代码先数，0 条就睡、不花一分钱；有才叫模型。整轮拿着账本锁（[LedgerTools.LOCK]）——
 * 同一时刻只有一个 agent 写账；别人正拿着就这次不跑，等下一轮。跑的时候挂在 [AgentActivity] 上，
 * 顶栏那枚印看得到。
 *
 * 模型在后台自己决定一切（放开模式，不问）：写进去的都是「自动归的」或「待确认」，
 * 事后都能在对话里改；用户确认过的它动不了（工具层挡着）。
 */
object Organizer {

    /** 最近这么久内到的先不整理：招行 / 掌生两条别被拆进两批，遮蔽版有时间补回真正文（DESIGN.md §10.7 第 3 条） */
    const val COOLDOWN_MILLIS = 10 * 60 * 1000L

    /** 一批最多几条；攒多了分几批，上一批的结果会出现在下一批的「最近流水」里（DESIGN.md §10.7 第 6 条） */
    const val BATCH = 30
    private const val MAX_BATCHES = 6

    /**
     * 「最近已有的流水」往回看几天。取一整周：花钱大多按周重复（周二健身、周末买菜、工作日午饭），
     * 看得到上周同一天、同一时段的那笔，没商户的也好归。也用来去重、找退款的原笔
     */
    private const val RECENT_DAYS = 7L

    /** 最多带几笔。按时间从新到旧取，超了砍掉的是最早那几天 —— 给够一周的量 */
    private const val RECENT_LIMIT = 200

    /** 开着打标签时，给它看多少个标签、每个几笔例子 */
    private const val TAG_LIMIT = 30
    private const val TAG_EXAMPLES = 3

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

        // 先让监听把通知栏扫一遍：丢了的实时回调、遮蔽后还没补回来的，趁这时候捞一把。扫完、存完才往下走
        Intake.sweep("organize")

        val before = System.currentTimeMillis() - if (respectCooldown) COOLDOWN_MILLIS else 0
        if (dao.countPendingRaws(before) == 0) return Result.Idle

        val profile = ProviderStore.flow(context).first()
        if (!profile.isConfigured) return Result.Skipped("还没配置模型")

        // 设置里「整理员自己打标签」：关着（默认）连 tags 参数都不给它（DESIGN.md §10.4）
        val tagging = LedgerSettings.organizerTags(context)
        val parsedBy = "${profile.model}@${LedgerPrompt.version(tagging)}"
        val tools = ToolRegistry(LedgerTools.forOrganizer(store, { parsedBy }, tagging))
        val system = if (tagging) LedgerPrompt.ORGANIZE_TAGGING else LedgerPrompt.ORGANIZE
        val loop = AgentLoop(ResponsesClient(profile), tools, system, context = LastTurns(1), maxSteps = 5)

        var run = AgentRun(kind = "organize", reason = reason, startedAt = System.currentTimeMillis(), model = profile.model)
        run = run.copy(id = dao.insertRun(run))
        var seen = emptySet<Long>()
        try {
            // 你在对话里说过的「8837 是老婆的卡」这类：对话模型记进记忆，整理员从这里读到（DESIGN.md §10.1「两个模型怎么传话」）
            val memories = MemoryBook.get(context).all()
            for (i in 0 until MAX_BATCHES) {
                val batch = dao.pendingRaws(before, BATCH)
                // 上一批交完了还剩同样几条：模型处理不了它们，别原地打转，留给下次
                if (batch.isEmpty() || batch.map { it.id }.toSet() == seen) break
                seen = batch.map { it.id }.toSet()

                val input = inputOf(store, batch, memories, tagging)
                trace(context, "$reason 第 ${i + 1} 批 · ${input.counts} · 约 ${input.text.length} 字")
                var failure: String? = null
                loop.run(Session(), input.text, ZonedDateTime.now()).collect { e ->
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

    /** 喂给整理 agent 的一批：[text] 是原文，[counts] 只数个数（写 trace 用，不落内容） */
    private class Input(val text: String, val counts: String)

    /** 这一轮喂给整理 agent 的全部背景。会变的都在这里，system 提示词保持不动 */
    private suspend fun inputOf(store: LedgerStore, batch: List<RawNotification>, memories: List<Memory>, tagging: Boolean): Input {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val recent = store.query(ExpenseQuery(fromDay = LedgerDays.dayInt(today.minusDays(RECENT_DAYS)), limit = RECENT_LIMIT)).reversed()
        val merchants = store.merchantMemory()
        val categories = store.categories()
        // 开着打标签才给：每个标签带最近几笔，它照着学
        val tags = if (!tagging) emptyList() else LedgerTags.byUse(store.tagUses()).take(TAG_LIMIT).map { name ->
            name to store.query(ExpenseQuery(tag = name, limit = TAG_EXAMPLES))
        }.filter { it.second.isNotEmpty() }
        val text = buildString {
            append("这一批原始通知（${batch.size} 条）：\n")
            batch.forEach { append(LedgerText.raw(store.noteOf(it), zone)).append('\n') }
            append("\n").append(LedgerText.categoryTree(categories)).append('\n')
            append("\n商户记忆（用户确认过的）：")
            append(if (merchants.isEmpty()) "还没有" else merchants.joinToString("；") { (m, c) -> "$m → $c" })
            append("\n\n关于用户记下的事（他说过的，认账时参考）：")
            if (memories.isEmpty()) append("还没有") else memories.forEach { append("\n- ").append(it.text) }
            if (tagging) {
                append("\n\n用户打过的标签（只用这些，照这几笔学）：")
                if (tags.isEmpty()) append("还没有，这次一个都不打")
                tags.forEach { (name, txns) ->
                    append("\n- ").append(name).append("：").append(txns.joinToString("；") { LedgerText.tagExample(it, zone) })
                }
            }
            append("\n\n最近几天已有的流水：")
            if (recent.isEmpty()) append("没有") else recent.forEach { append('\n').append(LedgerText.txn(it, zone)) }
        }
        return Input(
            text,
            "通知 ${batch.size} 条 · 类别 ${categories.size} 个 · 商户记忆 ${merchants.size} 条 · " +
                "记忆 ${memories.size} 条 · " + (if (tagging) "标签 ${tags.size} 个 · " else "") + "最近流水 ${recent.size} 笔"
        )
    }

    /**
     * 每批喂了几样东西（只数个数，不写内容）。只在 debug 包里写 —— 这台 ROM 看不到 logcat：
     *
     *     adb shell "run-as com.abc.daodian.debug cat files/organize_trace.txt"
     */
    private fun trace(context: Context, line: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(context.filesDir, TRACE_FILE)
            if (file.length() > TRACE_MAX_BYTES) file.delete()
            file.appendText("${LocalDateTime.now().format(TRACE_CLOCK)} $line\n")
        }
    }

    private const val TRACE_FILE = "organize_trace.txt"
    private const val TRACE_MAX_BYTES = 64 * 1024
    private val TRACE_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
}
