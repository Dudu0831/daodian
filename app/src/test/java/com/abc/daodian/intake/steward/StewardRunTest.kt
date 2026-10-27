package com.abc.daodian.intake.steward

import com.abc.daodian.AGENTS
import com.abc.daodian.assistant.memory.FakeMemoryBook
import com.abc.daodian.intake.core.CMB
import com.abc.daodian.intake.core.FakeHandoffs
import com.abc.daodian.intake.core.FakeInbox
import com.abc.daodian.intake.core.FakeRules
import com.abc.daodian.intake.core.NAMES
import com.abc.daodian.intake.core.SMS
import com.abc.daodian.intake.core.inbox.HandoffState
import com.abc.daodian.intake.core.inbox.InboxState.DONE
import com.abc.daodian.intake.core.inbox.InboxState.PENDING
import com.abc.daodian.intake.core.notice
import com.abc.daodian.intake.core.pipeline.Pipeline
import com.abc.daodian.intake.core.rule
import com.abc.daodian.intake.core.rule.RuleDraft
import com.abc.daodian.intake.core.steward.Destination
import com.abc.daodian.intake.plugin.Receipt
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.model.LlmClient
import com.abc.daodian.runtime.model.LlmEvent
import com.abc.daodian.runtime.model.LlmException
import com.abc.daodian.runtime.model.LlmRequest
import com.abc.daodian.runtime.model.ProviderProfile
import com.abc.daodian.runtime.model.StepOutput
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 管家跑一轮（真的 AgentRuntime + 照剧本回话的假网关）、交接、调度 */
class StewardRunTest {

    private val parcel = rule(1, RuleDraft(name = "快递", apps = listOf(SMS), bodyAny = listOf("取件码"), soft = "快递到了 18:00 提醒我取"))
    private val bank = rule(2, RuleDraft(name = "招行", apps = listOf(CMB), soft = "都交给记账"))
    private val rules = FakeRules(listOf(parcel, bank))
    private val inbox = FakeInbox()
    private val destinations = listOf(Destination("ledger", "记账", "动了钱的通知"), Destination("reminder", "提醒", "要用户去做的事"))
    private val now = ZonedDateTime.of(2026, 10, 26, 17, 45, 0, 0, ZoneId.of("Asia/Shanghai"))

    private val requests = mutableListOf<LlmRequest>()

    /** 每一步照剧本回：前几步调工具，剧本用完就说「好了」 */
    private fun install(vararg script: List<Item.ToolCall>, configured: Boolean = true, fail: Boolean = false) {
        val steps = script.toMutableList()
        val client = object : LlmClient {
            override fun step(request: LlmRequest): Flow<LlmEvent> = flow {
                requests += request
                if (fail) throw LlmException("502 网关挂了")
                emit(LlmEvent.Done(StepOutput(if (steps.isEmpty()) "好了" else "", steps.removeFirstOrNull().orEmpty(), "")))
            }
        }
        val profile = if (configured) ProviderProfile("https://example.test/v1", "key", "test-model") else ProviderProfile("", "", "")
        AgentRuntime.install(AGENTS, { profile }, { FakeMemoryBook("他下班是 18:00") }, strict = true, clientOf = { client })
    }

    private fun call(name: String, args: String) = Item.ToolCall("c${args.hashCode()}", name, args)

    private fun steward() = Steward(inbox, rules, destinations, NAMES, now = { now })

    /** 两条进收件箱：n1 快递短信（中 r1），n2 招行（中 r2） */
    private fun arrive() = runBlocking {
        val pipeline = Pipeline.standard(rules, inbox, {}, {}, now = { 0 })
        pipeline.run(notice(SMS, "菜鸟驿站", "取件码 3-2-1088", key = "a"))
        pipeline.run(notice(CMB, "招商银行", "您尾号 8837 的卡支出 58.00 元", key = "b"))
    }

    @Test
    fun `decisions land in one go, the model sees rules and memory in the background`() = runBlocking {
        arrive()
        install(
            listOf(
                call("hand_off", """{"notice":1,"rules":[],"to":"reminder","note":"18:00 提醒取快递，标题带 3-2-1088"}"""),
                call("hand_off", """{"notice":2,"rules":[2],"to":"ledger","note":""}""")
            )
        )
        assertEquals(Steward.Result.Done(more = false), steward().runOnce())
        assertEquals(listOf(DONE, DONE), inbox.rows.values.map { it.state })
        assertEquals(listOf("reminder", "ledger"), inbox.handoffs.map { it.to })
        assertEquals("交给 reminder", inbox.decided[1L to 1L])

        val first = requests.first()
        val background = first.background.orEmpty()
        assertTrue(background, background.contains("- r1 · 快递：快递到了 18:00 提醒我取"))
        assertTrue(background, background.contains("- reminder · 提醒：要用户去做的事"))
        assertTrue(background, background.contains("他下班是 18:00"))
        val input = first.input.filterIsInstance<Item.UserMessage>().single().text
        assertTrue(input, input.contains("n1 · 短信 ·") && input.contains("n2 · 招商银行 ·"))
    }

    @Test
    fun `a wrong call is sent back and the model can fix it`() = runBlocking {
        arrive()
        install(
            listOf(call("hand_off", """{"notice":1,"rules":[],"to":"parcel","note":""}""")),
            listOf(call("hand_off", """{"notice":1,"rules":[],"to":"reminder","note":"18:00 取快递"}"""), call("ignore", """{"notice":2,"rules":[],"why":"测试"}"""))
        )
        steward().runOnce()
        val toolResults = requests[1].input.filterIsInstance<Item.ToolResult>()
        assertTrue(toolResults.toString(), toolResults.single().output.contains("没有「parcel」这个去处"))
        assertEquals("不相关：测试", inbox.decided[2L to 2L])
        assertEquals(listOf(DONE, DONE), inbox.rows.values.map { it.state })
    }

    @Test
    fun `left without a say goes back with one more try and asks to run again`() = runBlocking {
        arrive()
        install(listOf(call("hand_off", """{"notice":2,"rules":[],"to":"ledger","note":""}""")))
        assertEquals(Steward.Result.Done(more = true), steward().runOnce())
        assertEquals(PENDING to 1, inbox.rows.getValue(1).let { it.state to it.attempts })
        assertEquals(DONE, inbox.rows.getValue(2).state)
    }

    @Test
    fun `a failed run writes nothing and costs no try`() = runBlocking {
        arrive()
        install(fail = true)
        val result = steward().runOnce()
        assertTrue(result.toString(), result is Steward.Result.Failed)
        assertEquals(listOf(PENDING to 0, PENDING to 0), inbox.rows.values.map { it.state to it.attempts })
        assertTrue(inbox.handoffs.isEmpty())
    }

    @Test
    fun `no model configured leaves the inbox alone`() = runBlocking {
        arrive()
        install(configured = false)
        assertEquals(Steward.Result.NotConfigured, steward().runOnce())
        assertEquals(listOf(PENDING, PENDING), inbox.rows.values.map { it.state })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a stopped rule is marked without asking the model`() = runBlocking {
        arrive()
        rules.list = rules.list.map { it.copy(enabled = false) }
        install()
        assertEquals(Steward.Result.Done(more = false), steward().runOnce())
        assertTrue(requests.isEmpty())
        assertEquals("不相关：${Steward.STOPPED}", inbox.decided[1L to 1L])
        assertEquals(listOf(DONE, DONE), inbox.rows.values.map { it.state })
    }

    @Test
    fun `nothing pending is idle`() = runBlocking {
        install()
        assertEquals(Steward.Result.Idle, steward().runOnce())
    }

    // ---------------- 交接 ----------------

    @Test
    fun `the dispatcher hands each place its own, receipts are written back`() = runBlocking {
        val handoffs = FakeHandoffs().apply {
            add(1, "ledger")
            add(2, "reminder")
            add(3, "reminder")
            add(4, "gone")
        }
        val got = mutableMapOf<String, List<Long>>()
        val dispatcher = Dispatcher(
            handoffs,
            mapOf(
                "ledger" to { list -> got["ledger"] = list.map { it.handoffId }; list.map { Receipt(it.handoffId, "待整理") } },
                "reminder" to { list -> got["reminder"] = list.map { it.handoffId }; listOf(Receipt(2, "18:00 · 取快递", "reminder/edit?id=9")) }
            ),
            NAMES
        )
        assertEquals(2, dispatcher.runOnce())
        assertEquals(mapOf("ledger" to listOf(1L), "reminder" to listOf(2L, 3L)), got)
        assertEquals(HandoffState.DELIVERED to "待整理", handoffs.rows.getValue(1).let { it.state to it.receipt })
        assertEquals(HandoffState.DELIVERED, handoffs.rows.getValue(2).state)
        assertEquals(HandoffState.DUE to Dispatcher.NO_RECEIPT, handoffs.rows.getValue(3).let { it.state to it.error })
        assertTrue(handoffs.rows.getValue(4).error!!.contains("没有「gone」"))
    }

    @Test
    fun `a place that throws is tried three times, then failed`() = runBlocking {
        val handoffs = FakeHandoffs().apply { add(1, "ledger") }
        val dispatcher = Dispatcher(handoffs, mapOf("ledger" to { _ -> error("库坏了") }), NAMES)
        repeat(3) { dispatcher.runOnce() }
        assertEquals(HandoffState.FAILED to "库坏了", handoffs.rows.getValue(1).let { it.state to it.error })
        dispatcher.runOnce()
        assertEquals(3, handoffs.rows.getValue(1).due.attempts)
    }

    // ---------------- 调度 ----------------

    @Test
    fun `pokes inside the window run once, more asks for another round`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runs = AtomicInteger()
        val scheduler = StewardScheduler(scope, { runs.incrementAndGet() == 1 }, debounceMillis = 100)
        delay(50)
        repeat(5) { scheduler.poke(); delay(10) }
        delay(600)
        scope.cancel()
        assertEquals("五下合成一轮，它说还有就再跑一轮", 2, runs.get())
    }

    @Test
    fun `a poke right after it is built is not lost`() = runBlocking {
        val runs = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Default)
        // 冷启动就是这样：刚建好就叫，收的那头还没起来
        StewardScheduler(scope, { runs.incrementAndGet(); false }, debounceMillis = 100).poke()
        delay(500)
        scope.cancel()
        assertEquals(1, runs.get())
    }
}
