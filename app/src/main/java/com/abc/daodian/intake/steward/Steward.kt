package com.abc.daodian.intake.steward

import com.abc.daodian.intake.core.ports.InboxRepository
import com.abc.daodian.intake.core.ports.RuleRepository
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.steward.Decision
import com.abc.daodian.intake.core.steward.DecisionBuffer
import com.abc.daodian.intake.core.steward.Destination
import com.abc.daodian.intake.core.steward.Settlement
import com.abc.daodian.intake.core.steward.StewardInput
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.runtime.Input
import com.abc.daodian.runtime.RunResult
import com.abc.daodian.runtime.context.Background
import com.abc.daodian.runtime.memory.Memory
import com.abc.daodian.runtime.memory.MemoryPreamble
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException

/**
 * 管家跑一轮（DESIGN.md §12.4）：领一批待办 → 拼输入 → 跑 [StewardAgent] → 一个事务落库（决定、交接、状态）。
 * 同一时刻只跑一个，由 [StewardScheduler] 管；这里不拿锁。
 *
 * 跑模型那一下可以换（[ask]），测试里换成假的网关，和真机走同一条路
 *
 * @param destinations 去处清单，照 `Modules.kt` 的顺序
 */
class Steward(
    private val inbox: InboxRepository,
    private val rules: RuleRepository,
    private val destinations: List<Destination>,
    private val names: AppNames,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
    private val configured: suspend () -> Boolean = { AgentRuntime.profile().isConfigured },
    private val memories: suspend () -> List<Memory> = { AgentRuntime.memories() },
    private val ask: suspend (Agent, Input, Background) -> RunResult = { agent, input, background -> AgentRuntime.runOnce(agent, input, background) }
) {

    sealed interface Result {
        /** 没有待办 */
        data object Idle : Result
        /** 没配模型：不领，待办留着 */
        data object NotConfigured : Result
        /** 跑完落了库。[more]：还有该接着办的（这一批满了、有没覆盖全的） */
        data class Done(val more: Boolean) : Result
        /** 整批跑失败，全放回待办，次数不加 */
        data class Failed(val why: String) : Result
    }

    suspend fun runOnce(): Result {
        if (!configured()) return Result.NotConfigured
        val at = now()
        val millis = at.toInstant().toEpochMilli()
        val batch = inbox.claim(BATCH, millis)
        if (batch.isEmpty()) return Result.Idle

        val active = rules.enabled()
        val buffer = DecisionBuffer(batch, destinations.map { it.id })
        // 中了的规则后来停了、删了：管家看不到它的软规则，代码直接标不相关
        val live = active.map { it.id }.toSet()
        batch.forEach { n -> (n.rules - live).takeIf { it.isNotEmpty() }?.let { buffer.offer(Decision.Ignore(n.id, it, STOPPED)) } }
        val left = buffer.remaining()
        val open = batch.mapNotNull { n -> left[n.id]?.let { n.copy(rules = it) } }

        var failure: String? = null
        val settlement = if (open.isEmpty()) buffer.settle() else {
            val standing = StewardInput.standing(destinations, active)
            val background = Background { MemoryPreamble.of(memories(), null, listOf(standing)) }
            val input = Input(StewardInput.batch(open, names, at.zone), at)
            val result = try {
                ask(StewardAgent.build(buffer, destinations.map { it.id }), input, background)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null.also { failure = e.message ?: e.javaClass.simpleName }
            }
            when (result) {
                is RunResult.Done -> buffer.settle()
                is RunResult.Failed -> Settlement.failed(batch).also { failure = result.reason }
                RunResult.NotConfigured -> Settlement.failed(batch).also { failure = "还没配置模型" }
                null -> Settlement.failed(batch)
            }
        }
        inbox.settle(settlement, millis)
        failure?.let { return Result.Failed(it) }
        return Result.Done(more = batch.size == BATCH || settlement.notices.any { it.state == InboxState.PENDING })
    }

    companion object {
        /** 一批最多几条 */
        const val BATCH = 20

        const val STOPPED = "规则停了"
    }
}
