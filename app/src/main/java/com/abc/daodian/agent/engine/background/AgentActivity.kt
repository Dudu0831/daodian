package com.abc.daodian.agent.engine.background

import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.agent.engine.tool.ToolContext
import com.abc.daodian.agent.engine.tool.ToolEffect
import com.abc.daodian.agent.engine.tool.ToolOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 后台 agent 的两件公共设施：**谁在跑**（给界面看）和**锁**（同一样东西同一时刻只让一个 agent 写）。
 *
 * 不认识任何具体业务 —— 记账整理是第一个用户，以后别的后台 agent（比如整理提醒）照样挂上来：
 * 起一把自己的锁（[lock]），跑的时候包一层 [track]，顶栏那枚印就会知道。
 *
 * 只在一个进程里有效：WorkManager 的任务和界面在同一个进程，够用了。
 */
object AgentActivity {

    /** 一个正在跑的后台 agent。[label] 是给人看的，比如「整理账目」 */
    data class Running(val id: String, val label: String, val startedAt: Long)

    private val _running = MutableStateFlow<List<Running>>(emptyList())

    /** 正在跑的，先开始的在前。空 = 后台安静 */
    val running: StateFlow<List<Running>> = _running.asStateFlow()

    private val locks = HashMap<String, Mutex>()

    /**
     * 按名字拿一把锁，同名同一把。锁的是「那一样东西」（比如账本），不是某个 agent ——
     * 后台整理和对话里改账都要先拿 `ledger` 这把，所以两边不会同时写。
     */
    @Synchronized
    fun lock(name: String): Mutex = locks.getOrPut(name) { Mutex() }

    /** 跑 [block] 期间挂在 [running] 上，结束（包括抛出、被取消）就摘掉 */
    suspend fun <T> track(id: String, label: String, block: suspend () -> T): T {
        val me = Running(id, label, System.currentTimeMillis())
        _running.update { it + me }
        try {
            return block()
        } finally {
            _running.update { list -> list.filterNot { it === me } }
        }
    }

    /**
     * 拿不到锁就不跑，返回 null —— 后台任务用它：别人正在写就等下一轮，不排队堆积。
     * 拿到了就挂上 [running] 跑完。
     */
    suspend fun <T> tryRun(lockName: String, id: String, label: String, block: suspend () -> T): T? {
        val mutex = lock(lockName)
        if (!mutex.tryLock()) return null
        try {
            return track(id, label, block)
        } finally {
            mutex.unlock()
        }
    }

    /** 排队等锁再跑：对话里的写操作用它，后台整理正在写时等它写完 */
    suspend fun <T> queued(lockName: String, block: suspend () -> T): T = lock(lockName).withLock { block() }
}

/**
 * 给写工具套一把锁：执行前排队拿 [lockName]，拿到才写。对话里的记账工具这么包 ——
 * 后台整理正拿着账本那把锁时，对话里的改账等它写完再动手，两边不会交叉写。
 *
 * 后台 agent 自己的工具**不要**包：它跑的时候已经拿着整把锁了，Mutex 不可重入，包了会把自己锁死。
 */
class LockedTool(private val inner: Tool, private val lockName: String) : Tool by inner {

    override suspend fun execute(arguments: String, context: ToolContext): ToolOutcome =
        if (inner.effect == ToolEffect.READ) inner.execute(arguments, context)
        else AgentActivity.queued(lockName) { inner.execute(arguments, context) }
}
