package com.abc.daodian.runtime

import com.abc.daodian.runtime.ask.AskUserTool
import com.abc.daodian.runtime.ask.Asker
import com.abc.daodian.runtime.context.Background
import com.abc.daodian.runtime.context.Preamble
import com.abc.daodian.runtime.engine.AgentEvent
import com.abc.daodian.runtime.engine.AgentLoop
import com.abc.daodian.runtime.engine.Session
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.memory.Memory
import com.abc.daodian.runtime.memory.MemoryBackend
import com.abc.daodian.runtime.memory.MemoryPreamble
import com.abc.daodian.runtime.model.ApiHealth
import com.abc.daodian.runtime.model.LlmClient
import com.abc.daodian.runtime.model.LlmRequest
import com.abc.daodian.runtime.model.ProviderProfile
import com.abc.daodian.runtime.model.ResponsesClient
import com.abc.daodian.runtime.tool.Tool
import com.abc.daodian.runtime.tool.ToolContext
import com.abc.daodian.runtime.tool.ToolEffect
import com.abc.daodian.runtime.tool.ToolOutcome
import com.abc.daodian.runtime.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 现在用哪个模型。配置存在哪（DataStore）是 app 的事，装进 [AgentRuntime] 时给 */
fun interface ModelSource {
    suspend fun current(): ProviderProfile
}

/** 每次真要发请求前看一眼发的是什么（垫了什么、原样带了哪几轮）。只给调试记录用，app 决定写到哪 */
fun interface Tracer {
    fun request(agent: Agent, preamble: Preamble?, sent: List<Turn>, request: LlmRequest)
}

/**
 * 跑 agent 的地方。对话页、桌面速记、整理账目、派活、整理记忆都从这里跑，不再各自拼循环。
 *
 * 跑一次它替你办掉这几件：读当前的模型配置、按配置拿一个共用的客户端（不再每跑一次新建一个连接池）、
 * 建循环、每次请求前交给 [Tracer]、一轮结束记进 [ApiHealth]（顶栏那枚印）。
 * debug 包里（[install] 的 strict）还会先查：这个 agent 在花名册上、手上的工具和提示词对得上、问不了人的没带 `ask_user` ——
 * 对不上直接崩，当场发现。
 *
 * 另外两件后台公共设施也在这里：**谁在跑**（[running]，给界面看）和**按名字的锁**（[exclusive] / [queued]）。
 * 只在一个进程里有效：WorkManager 的任务和界面在同一个进程，够用了。
 */
object AgentRuntime {

    private class Setup(
        val agents: List<AgentInfo>,
        val models: ModelSource,
        val memory: () -> MemoryBackend,
        val tracer: Tracer,
        val strict: Boolean,
        val clientOf: (ProviderProfile) -> LlmClient
    )

    @Volatile
    private var setup: Setup? = null

    /**
     * `DaodianApp` 启动时装一次（Worker、Receiver 都在 Application 之后跑，拿得到）。
     *
     * @param agents 花名册（根目录 `Agents.kt`）
     * @param memory 记忆库，用到时才取 —— 进程可能是闹钟拉起的，装的时候别去碰库
     * @param strict debug 包开：花名册、工具、问人三样对不上就崩
     * @param clientOf 按配置建客户端。测试里换成假的
     */
    fun install(
        agents: List<AgentInfo>,
        models: ModelSource,
        memory: () -> MemoryBackend,
        tracer: Tracer = Tracer { _, _, _, _ -> },
        strict: Boolean = false,
        clientOf: (ProviderProfile) -> LlmClient = ::ResponsesClient
    ) {
        require(agents.map { it.id }.distinct().size == agents.size) { "花名册上 id 重复：${agents.map { it.id }}" }
        synchronized(this) { cached = null }
        setup = Setup(agents, models, memory, tracer, strict, clientOf)
    }

    private fun setup(): Setup = checkNotNull(setup) { "AgentRuntime 还没装（DaodianApp.onCreate）" }

    /** 花名册：系统里一共这几个 agent */
    val agents: List<AgentInfo> get() = setup?.agents.orEmpty()

    /** 现在的模型配置。后台 agent 没配好就别跑；落库时记「谁整理的」也用它 */
    suspend fun profile(): ProviderProfile = setup().models.current()

    /** 关于他记下的事（全部） */
    suspend fun memories(): List<Memory> = setup().memory().all()

    /** 只垫记忆的背景（「老地方」「晚点」听得懂）。派活用；对话页、速记另外还垫摘要和模块现状 */
    val memory: Background = Background { MemoryPreamble.of(memories(), null) }

    /**
     * 前台用：一轮的事件流，界面边收边画（对话页、桌面速记）。
     * 没配模型也照样跑 —— 调用会失败，失败那一下界面要画出来（「手动填一条」）。
     */
    fun run(
        agent: Agent,
        session: Session,
        input: Input,
        asker: Asker = Asker.NONE,
        background: Background = Background.NONE
    ): Flow<AgentEvent> = flow {
        emitAll(events(agent, profile(), session, input, asker, background))
    }

    /**
     * 后台用：跑完一轮，收成一个结果。没配模型直接返回 [RunResult.NotConfigured]，不打网络。
     * 问不了人（`ask_user` 不该在它手上）；每段对话都是新的，只有这一句。
     *
     * [onOutcome]：每个工具一办完就告诉你（整理账目边跑边数记了几笔：跑到一半被停，已经记下的也算数）
     */
    suspend fun runOnce(
        agent: Agent,
        input: Input,
        background: Background = Background.NONE,
        onOutcome: (ToolOutcome) -> Unit = {}
    ): RunResult {
        val profile = profile()
        if (!profile.isConfigured) return RunResult.NotConfigured
        val outcomes = mutableListOf<ToolOutcome>()
        var end: RunResult? = null
        events(agent, profile, Session(), input, Asker.NONE, background).collect { e ->
            when (e) {
                is AgentEvent.ToolFinished -> {
                    outcomes += e.outcome
                    onOutcome(e.outcome)
                }
                is AgentEvent.Finished -> end = RunResult.Done(e.turn, e.stop, outcomes.toList())
                is AgentEvent.Failed -> end = RunResult.Failed(e.turn, e.reason, e.cause, outcomes.toList())
                else -> Unit
            }
        }
        // 循环以恰好一个 Finished 或 Failed 结束；被取消的话走不到这里
        return checkNotNull(end) { "「${agent.info.label}」这一轮没有结束事件" }
    }

    private fun events(
        agent: Agent,
        profile: ProviderProfile,
        session: Session,
        input: Input,
        asker: Asker,
        background: Background
    ): Flow<AgentEvent> {
        val s = setup()
        if (s.strict) check(s, agent)
        val loop = AgentLoop(
            client(s, profile), ToolRegistry(agent.tools), agent.prompt.text, agent.context, agent.maxSteps,
            onRequest = { preamble, sent, request -> s.tracer.request(agent, preamble, sent, request) }
        )
        return loop.run(session, input.text, input.at, asker, input.trigger, background, input.images)
            .onEach { if (it is AgentEvent.Finished || it is AgentEvent.Failed) ApiHealth.record(it) }
    }

    /** debug 包里跑之前查一遍：在花名册上、提示词是它自己的、工具对得上、问不了人的没带 ask_user */
    private fun check(s: Setup, agent: Agent) {
        val info = agent.info
        check(s.agents.any { it === info }) { "「${info.label}」（${info.id}）不在花名册上：根目录 Agents.kt 加一行" }
        check(agent.prompt in info.prompts) { "「${info.label}」跑的提示词（${agent.prompt.version}）不在它的 prompts 里" }
        val have = agent.tools.map { it.name }.toSet()
        val written = agent.prompt.tools
        check(have == written) {
            "「${info.label}」手上的工具和提示词（${agent.prompt.version}）对不上：多了 ${have - written}，少了 ${written - have}。" +
                "去改它的提示词，连同 Prompt.tools"
        }
        check(info.canAsk || AskUserTool.NAME !in have) { "「${info.label}」问不了人，手上不该有 ${AskUserTool.NAME}" }
    }

    private var cached: Pair<ProviderProfile, LlmClient>? = null

    /**
     * 按配置缓存一份客户端，大家共用 —— [ResponsesClient] 自带一个 OkHttp 客户端，跑一次新建一个等于白扔掉连接池。
     * 配置一改，下一次跑就换过去
     */
    @Synchronized
    private fun client(s: Setup, profile: ProviderProfile): LlmClient {
        cached?.let { (p, c) -> if (p == profile) return c }
        return s.clientOf(profile).also { cached = profile to it }
    }

    // ---------------- 谁在跑、按名字的锁 ----------------

    /** 一个正在跑的后台 agent。[label] 是给人看的，比如「整理账目」 */
    data class Running(val id: String, val label: String, val startedAt: Long)

    private val _running = MutableStateFlow<List<Running>>(emptyList())

    /** 正在跑的，先开始的在前。空 = 后台安静。顶栏那枚印外面转一圈细线靠它 */
    val running: StateFlow<List<Running>> = _running.asStateFlow()

    private val locks = HashMap<String, Mutex>()

    /**
     * 按名字拿一把锁，同名同一把。锁的是「那一样东西」（比如账本），不是某个 agent ——
     * 后台整理和对话里改账都要先拿 `ledger` 这把，所以两边不会同时写。
     */
    @Synchronized
    private fun lock(name: String): Mutex = locks.getOrPut(name) { Mutex() }

    /** 跑 [block] 期间挂在 [running] 上，结束（包括抛出、被取消）就摘掉 */
    private suspend fun <T> track(id: String, label: String, block: suspend () -> T): T {
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
     * 拿到了就挂上 [running] 跑完（顶栏看得到）。[runOnce] 自己不挂，要挂的包一层这个
     */
    suspend fun <T> exclusive(lockName: String, id: String, label: String, block: suspend () -> T): T? {
        val mutex = lock(lockName)
        if (!mutex.tryLock()) return null
        try {
            return track(id, label, block)
        } finally {
            mutex.unlock()
        }
    }

    /** 排队等锁再跑：对话里的写操作用它（[LockedTool]），后台整理正在写时等它写完 */
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
        else AgentRuntime.queued(lockName) { inner.execute(arguments, context) }
}
