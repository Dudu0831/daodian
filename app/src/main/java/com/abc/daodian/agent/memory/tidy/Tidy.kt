package com.abc.daodian.agent.memory.tidy

import android.content.Context
import com.abc.daodian.agent.conversation.ChatAgent
import com.abc.daodian.agent.conversation.data.ChatStore
import com.abc.daodian.agent.engine.AgentEvent
import com.abc.daodian.agent.engine.AgentLoop
import com.abc.daodian.agent.engine.Session
import com.abc.daodian.agent.engine.Turn
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.agent.engine.context.Compaction
import com.abc.daodian.agent.engine.context.LastTurns
import com.abc.daodian.agent.engine.tool.ToolRegistry
import com.abc.daodian.agent.memory.MemoryBook
import com.abc.daodian.agent.memory.MemorySettings
import com.abc.daodian.agent.model.ResponsesClient
import com.abc.daodian.agent.model.provider.ApiHealth
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.agent.memory.data.TidyRunEntity
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 后台整理对话：抽记忆，攒够了顺手把早先的对话压成摘要。一次模型调用两件事一起办。见 DESIGN.md §6.9
 *
 * 两个时机：
 *  - **闲下来**：对话页每说完一轮，把「10 分钟后整理」往后推（[TidyWorker]）。一直在聊就一直不跑，停下来才跑。
 *  - **攒够了**：摘要之后原样喂的轮次到了 [Compaction.AT_TURNS]，说完这一轮当场在后台压，不等闲下来。
 *
 * 设置里关了「聊完自己整理」：闲下来那条路不跑；攒够了照样压摘要，但记忆不动。
 *
 * 失败了什么都不坏：进度不动，下次再来；压缩一直不成，对话照样用最近 [Compaction.HARD_CAP] 轮。
 * 整理和对话不抢锁 —— 只写记忆和 tidy_runs，不碰 `chat_items`。
 */
object Tidy {

    private const val LOCK = "chat-tidy"

    /** 上次失败后这么久之内，「攒够了」那条路不再试（闲下来那条照试）：网关挂了别每说一句就白打一次 */
    private const val BACKOFF_MILLIS = 30 * 60 * 1000L

    /** 对话页正在跑一轮。闲下来那条路从库里读对话，这时最后一轮还没说完，不算 */
    @Volatile var chatBusy = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed interface Result {
        data object Idle : Result
        data object Busy : Result
        data class Skipped(val why: String) : Result
        data class Done(val run: TidyRunEntity) : Result
    }

    /** 对话页说完一轮（[turns] 是那一刻的完整记录）：推迟闲下来的整理；攒够了就当场压 */
    fun afterTurn(context: Context, turns: List<Turn>) {
        val app = context.applicationContext
        scope.launch {
            if (MemorySettings.autoTidy(app)) TidyWorker.schedule(app)
            val log = TidyLog.get(app)
            val after = log.digest()?.through ?: 0
            if (Compaction.cut(turns.filter { it.id > after }) { viewSize(app, it) } == null) return@launch
            val last = log.lastRun()
            val failed = last?.error != null && last.error != CANCELLED
            if (failed && System.currentTimeMillis() - last!!.startedAt < BACKOFF_MILLIS) return@launch
            runCatching { run(app, REASON_TURN, turns) }
        }
    }

    /**
     * 整理一次。[turns] 为 null 时从库里读（闲下来那条路，app 可能已经被杀过）。
     * 要压就只看要压的那段（摘要之后到切点）；不压就看记忆进度之后的全部。
     */
    suspend fun run(context: Context, reason: String, turns: List<Turn>? = null): Result {
        val app = context.applicationContext
        return AgentActivity.tryRun(LOCK, "tidy", "整理记忆") { tidy(app, reason, turns) } ?: Result.Busy
    }

    private suspend fun tidy(context: Context, reason: String, given: List<Turn>?): Result {
        val all = given ?: ChatStore.get(context).load().let { if (chatBusy) it.dropLast(1) else it }
        if (all.isEmpty()) return Result.Idle
        val profile = ProviderStore.flow(context).first()
        if (!profile.isConfigured) return Result.Skipped("还没配置模型")

        val log = TidyLog.get(context)
        val book = MemoryBook.get(context)
        val stale = ChatAgent.staleTools(context)
        val digest = log.digest()?.takeIf { it.through < all.last().id }
        val memoryThrough = log.memoryThrough(all.last().id)

        val cut = Compaction.cut(all.filter { it.id > (digest?.through ?: 0) }) { viewSize(context, it) }
        val compact = cut != null
        // 关着自动整理：只在要压的时候跑，而且只写摘要
        val remember = MemorySettings.autoTidy(context)
        if (!compact && !remember) return Result.Skipped("关着自动整理")
        val pending = if (cut != null) all.filter { it.id > (digest?.through ?: 0) && it.id <= cut }
        else all.filter { it.id > memoryThrough }
        if (pending.isEmpty()) return Result.Idle
        val turns = TidyText.take(pending, stale)

        var run = log.start(
            TidyRunEntity(
                reason = reason, startedAt = System.currentTimeMillis(), model = "${profile.model}@${TidyPrompt.VERSION}",
                fromTurnId = turns.first().id, toTurnId = turns.last().id
            )
        )
        var saved: SaveTidyTool.Saved? = null
        var failure: String? = null
        try {
            val loop = AgentLoop(
                ResponsesClient(profile), ToolRegistry(listOf(SaveTidyTool(book, compact, remember))), TidyPrompt.SYSTEM,
                context = LastTurns(1), maxSteps = 3
            )
            val input = TidyText.input(book.all(), digest, turns, compact, stale, remember)
            loop.run(Session(), input, ZonedDateTime.now()).collect { e ->
                when (e) {
                    is AgentEvent.ToolFinished -> (e.outcome.payload as? SaveTidyTool.Saved)?.let { saved = it }
                    is AgentEvent.Failed -> { failure = e.reason; ApiHealth.record(e) }
                    is AgentEvent.Finished -> ApiHealth.record(e)
                    else -> Unit
                }
            }
            val s = saved
            run = if (s == null) {
                run.copy(error = failure ?: "没交上整理结果")
            } else {
                run.copy(
                    // 关着的时候记忆进度不动（再打开时会从那一刻接着看，见 MemorySettings）
                    memoryThrough = if (remember) maxOf(memoryThrough, turns.last().id) else memoryThrough,
                    digestThrough = if (s.summary != null) turns.last().id else null,
                    digestThroughAt = if (s.summary != null) turns.last().input.at.toInstant().toEpochMilli() else null,
                    digest = s.summary,
                    added = s.applied.added.size, updated = s.applied.updated.size, removed = s.applied.removed.size
                )
            }
        } catch (t: Throwable) {
            // 被取消（闲下来那次跑着时又说了一句，WorkManager 换成新的一次）不算失败，不触发退避
            run = run.copy(error = if (t is CancellationException) CANCELLED else t.message ?: t.javaClass.simpleName)
            if (t is CancellationException) throw t
        } finally {
            run = run.copy(finishedAt = System.currentTimeMillis())
            withContext(NonCancellable) { log.finish(run) }
        }
        return Result.Done(run)
    }

    /** 这几轮喂给模型时有多大：折过旧图、旧查询之后的，和对话页真正发出去的一致 */
    private fun viewSize(context: Context, turns: List<Turn>): Int = Compaction.chars(ChatAgent.foldView(context, turns))

    const val REASON_TURN = "turn"
    const val REASON_IDLE = "idle"

    /** tidy_runs.error 写这个 = 被取消了，不是失败 */
    private const val CANCELLED = "取消了"
}
