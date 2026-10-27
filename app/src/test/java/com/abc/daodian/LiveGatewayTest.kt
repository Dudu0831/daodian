package com.abc.daodian

import com.abc.daodian.assistant.AssistantAgent
import com.abc.daodian.assistant.memory.FakeMemoryBook
import com.abc.daodian.assistant.memory.tidy.SaveTidyTool
import com.abc.daodian.assistant.memory.tidy.TidyAgent
import com.abc.daodian.assistant.memory.tidy.TidyText
import com.abc.daodian.intake.core.CMB
import com.abc.daodian.intake.core.FakeInbox
import com.abc.daodian.intake.core.FakeRules
import com.abc.daodian.intake.core.NAMES
import com.abc.daodian.intake.core.SMS
import com.abc.daodian.intake.core.WECHAT
import com.abc.daodian.intake.core.notice
import com.abc.daodian.intake.core.pipeline.Pipeline
import com.abc.daodian.intake.core.rule
import com.abc.daodian.intake.core.rule.RuleDraft
import com.abc.daodian.intake.core.steward.Destination
import com.abc.daodian.intake.steward.Steward
import com.abc.daodian.ledger.capture.LedgerNoticeTarget
import com.abc.daodian.ledger.domain.LedgerBackend
import com.abc.daodian.ledger.tools.LedgerTools
import com.abc.daodian.reminder.domain.ReminderPlan
import com.abc.daodian.reminder.notice.ReminderNoticeAgent
import com.abc.daodian.reminder.notice.ReminderNoticePrompt
import com.abc.daodian.reminder.notice.ReminderNoticeTarget
import com.abc.daodian.reminder.relay.RelayAgent
import com.abc.daodian.reminder.relay.RelayPrompt
import com.abc.daodian.reminder.tools.CreateReminderTool
import com.abc.daodian.reminder.tools.ReminderTools
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentRuntime
import com.abc.daodian.runtime.Input
import com.abc.daodian.runtime.RunResult
import com.abc.daodian.runtime.context.Preamble
import com.abc.daodian.runtime.engine.AgentEvent
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Session
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.memory.MemoryBackend
import com.abc.daodian.runtime.model.LlmEvent
import com.abc.daodian.runtime.model.ProviderProfile
import com.abc.daodian.runtime.payloads
import com.abc.daodian.runtime.reply
import com.abc.daodian.runtime.tool.ToolContext
import java.io.File
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Properties
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 打真网关：验证「工具结果回传 → 模型接着说」和「历史里的工具调用原样重放」这两件事网关认不认，
 * 以及几个 agent 的提示词在真模型上管不管用。要花钱、要联网，默认跳过。跑法（在项目根目录）：
 *
 *     DAODIAN_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveGatewayTest*' -i
 *
 * 配置读根目录的 secrets.properties；想换供应商不改文件，用环境变量覆盖：
 * `DAODIAN_BASE_URL` / `DAODIAN_KEY` / `DAODIAN_MODEL`。落库是假的，不碰手机。
 *
 * 和真机走同一条路：`AgentRuntime` 照 debug 包的规矩装（strict），agent 用各自的 `XxxAgent.build(...)` 建，
 * 助手的工具也是真机上那一套（自己的两样 + 提醒、记账给的）。
 */
class LiveGatewayTest {

    private lateinit var profile: ProviderProfile

    /** 这一次一共发了几次请求（runtime 每次真要发请求前交给 Tracer） */
    private var requests = 0

    /** 这几条用不着账本：建工具不碰它，模型真去查账就报出来（工具出错会回给模型，不会让测试崩） */
    private val noLedger = Proxy.newProxyInstance(LedgerBackend::class.java.classLoader, arrayOf(LedgerBackend::class.java)) { _, method, _ ->
        error("这几条用不着账本：${method.name}")
    } as LedgerBackend

    @Before
    fun setUp() {
        assumeTrue("没设 DAODIAN_LIVE=1，跳过", System.getenv("DAODIAN_LIVE") == "1")
        val file = File("../secrets.properties").takeIf { it.exists() } ?: File("secrets.properties")
        val p = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
        fun setting(env: String, key: String) = (System.getenv(env) ?: p.getProperty(key))?.trim().orEmpty()
        profile = ProviderProfile(
            baseUrl = setting("DAODIAN_BASE_URL", "LLM_BASE_URL").removeSuffix("/"),
            apiKey = setting("DAODIAN_KEY", "LLM_API_KEY"),
            model = setting("DAODIAN_MODEL", "LLM_MODEL"),
            // DAODIAN_API=chat 走 /chat/completions，不设走 /responses
            api = if (System.getenv("DAODIAN_API").equals("chat", ignoreCase = true)) ProviderProfile.Api.CHAT else ProviderProfile.Api.RESPONSES
        )
        assumeTrue("供应商没配全（secrets.properties 或环境变量）", profile.isConfigured)
    }

    /** 每条测试自己装一次：记忆库各用各的，请求数从 0 数 */
    private fun install(book: MemoryBackend = FakeMemoryBook()) {
        requests = 0
        AgentRuntime.install(AGENTS, { profile }, { book }, tracer = { _, _, _, _ -> requests++ }, strict = true)
    }

    /** 助手，带着真机上那一套工具（按 `Modules.kt` 的顺序）；建提醒落到 [commit] */
    private fun assistant(book: MemoryBackend, commit: suspend (ReminderPlan, ToolContext) -> Long = { _, _ -> 1001L }): Agent =
        AssistantAgent.build(AssistantAgent.tools(book, ReminderTools.forAssistant(commit) + LedgerTools.forAssistant(noLedger)))

    @Test
    fun `tool result round trip and replayed history`() = runBlocking {
        val committed = mutableListOf<String>()
        val book = FakeMemoryBook()
        install(book)
        val agent = assistant(book) { plan, _ ->
            committed += "${plan.title} @ ${plan.firstTriggerAt}"
            1001L
        }
        val session = Session()
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))

        // 第一轮：要走完「调工具 → 结果回传 → 模型收尾」
        val first = AgentRuntime.run(agent, session, Input("明天下午三点提醒我交房租", now)).onEach(::log).toList()
        println("第一轮发了 $requests 次请求，落库：$committed")
        val end1 = first.last()
        assertTrue("第一轮没正常结束：$end1", end1 is AgentEvent.Finished)
        assertEquals("应该恰好建一条", 1, committed.size)
        assertTrue("工具结果没回传给模型（只发了 $requests 次）", requests >= 2)

        // 第二轮：历史里带着上一轮的 function_call / output，网关得认
        requests = 0
        val second = AgentRuntime.run(agent, session, Input("我刚才让你提醒我什么？", now.plusMinutes(1))).onEach(::log).toList()
        val end2 = second.last()
        assertTrue("第二轮没正常结束：$end2", end2 is AgentEvent.Finished)
        val reply = (end2 as AgentEvent.Finished).turn.items.filterIsInstance<Item.AssistantMessage>().joinToString { it.text }
        println("第二轮回答：$reply")
        assertTrue("模型没从历史里认出房租：$reply", "房租" in reply)
        assertEquals("第二轮不该再建", 1, committed.size)
    }

    /**
     * 垫在前面的记忆（§6.9）：网关认不认开头那条「app 附上的背景」、模型拿不拿它听懂「晚点」；
     * 他说「记住…」时调不调 edit_memory。
     */
    @Test
    fun `background memory is used and edit_memory is called when asked`() = runBlocking {
        val committed = mutableListOf<String>()
        val book = FakeMemoryBook("他说的「晚点」一般指晚上 9 点")
        install(book)
        val agent = assistant(book) { plan, _ -> committed += plan.firstTriggerAt; 1001L }
        val session = Session()
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).withHour(15).withMinute(0)
        val background = { _: Session -> Preamble("你记得的关于他的事（m 编号 · 记下或改过的日子）：\n- m1 · 9月20日 · 他说的「晚点」一般指晚上 9 点") }

        val first = AgentRuntime.run(agent, session, Input("晚点提醒我给妈妈打电话", now), background = background).onEach(::log).toList()
        assertTrue("第一轮没正常结束：${first.last()}", first.last() is AgentEvent.Finished)
        println("建的：$committed")
        assertTrue("没按记忆排到 21:00：$committed", committed.singleOrNull()?.contains("T21:00") == true)

        val second = AgentRuntime.run(agent, session, Input("记住：我每周二晚上健身", now.plusMinutes(1)), background = background)
            .onEach(::log).toList()
        assertTrue("第二轮没正常结束：${second.last()}", second.last() is AgentEvent.Finished)
        println("记忆：${book.list.map { it.text }}")
        assertTrue("没记下健身：${book.list}", book.list.any { "健身" in it.text })
    }

    /**
     * 派活（reminder/relay）：她发来的一句，只带 create_reminder，问不了人。照 `Relay` 的跑法：`RelayAgent` + 只垫记忆。
     * 没说钟点的建成当天事项；她说「不用响铃」也照样响铃（gentle = false）；闲聊不建。
     */
    @Test
    fun `relay turns a chore into a day task and leaves chit-chat alone`() = runBlocking {
        install()
        val plans = mutableListOf<ReminderPlan>()
        val agent = RelayAgent.build { plan, _ -> plans += plan; 1001L }
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).withHour(15).withMinute(0)

        val task = AgentRuntime.runOnce(agent, Input(RelayPrompt.input("小美", "明天帮我取个快递，不用响铃", coded = false), now), AgentRuntime.memory)
        println("派活：$task\n建的：$plans")
        assertTrue("派活没正常结束：$task", task is RunResult.Done)
        assertEquals("工具交上来的就是建的那条", listOf(1001L), task.payloads<CreateReminderTool.Created>().map { it.reminderId })
        val plan = plans.singleOrNull()
        assertNotNull("应该恰好建一条：$plans", plan)
        assertTrue("没说钟点，应该是当天事项：$plan", plan!!.allDay)
        assertFalse("别人派来的事一律响铃：$plan", plan.gentle)

        plans.clear()
        val chitchat = AgentRuntime.runOnce(agent, Input(RelayPrompt.input("小美", "你在干嘛呀", coded = false), now), AgentRuntime.memory)
        println("闲聊：${chitchat.reply}")
        assertTrue("闲聊那句没正常结束：$chitchat", chitchat is RunResult.Done)
        assertTrue("闲聊不该建：$plans", plans.isEmpty())
    }

    /**
     * 通知管家（§12.4）照规则定去处：快递短信交提醒、附言带上 18:00；驿站的广告不相关；招行交记账；
     * 老婆带暗号的交提醒。再拿快递那条的附言喂提醒子代理，建出 18:00 的提醒
     */
    @Test
    fun `steward follows the soft rules and the reminder sub agent follows the note`() = runBlocking {
        install(FakeMemoryBook("他一般 18:00 下班"))
        val rules = FakeRules(
            listOf(
                rule(1, RuleDraft(name = "快递到了提醒取", apps = listOf(SMS), bodyAny = listOf("取件码", "驿站"), soft = "快递到了驿站或快递柜，当天 18:00 提醒我去取，标题带上取件码；广告、寄件优惠不算")),
                rule(2, RuleDraft(name = "招商银行记账", apps = listOf(CMB), soft = "这个 app 的通知都交给记账")),
                rule(3, RuleDraft(name = "老婆派的事", apps = listOf(WECHAT), title = "老婆", startsWith = "到点", soft = "她让我做的事建成提醒，一律响铃；闲聊、她自己的安排不算"))
            )
        )
        val inbox = FakeInbox()
        val pipeline = Pipeline.standard(rules, inbox, {}, {}, now = { 0 })
        pipeline.run(notice(SMS, "菜鸟驿站", "【菜鸟驿站】您的包裹已到幸福里驿站，凭取件码 3-2-1088 领取", key = "a"))
        pipeline.run(notice(SMS, "菜鸟驿站", "【菜鸟】双十一寄件 5 折，驿站寄快递更划算，退订回 T", key = "b"))
        pipeline.run(notice(CMB, "招商银行", "您尾号 8837 的信用卡 10 月 26 日 12:03 消费 58.00 元", key = "c"))
        pipeline.run(notice(WECHAT, "老婆(2条)", "[2条]老婆: 到点 晚上回来带瓶酱油", key = "d"))
        assertEquals(4, inbox.rows.size)

        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).withHour(15).withMinute(0)
        val destinations = listOf(
            Destination(LedgerNoticeTarget.ID, LedgerNoticeTarget.label, LedgerNoticeTarget.brief),
            Destination(ReminderNoticeTarget.ID, ReminderNoticeTarget.label, ReminderNoticeTarget.brief)
        )
        val result = Steward(inbox, rules, destinations, NAMES, now = { now }).runOnce()
        println("管家：$result\n交接：${inbox.handoffs}\n说法：${inbox.decided}")
        assertTrue("管家没正常结束：$result", result is Steward.Result.Done)
        assertEquals("四条都有说法", setOf(1L to 1L, 2L to 1L, 3L to 2L, 4L to 3L), inbox.decided.keys)
        val parcel = inbox.handoffs.single { it.noticeId == 1L }
        assertEquals("reminder", parcel.to)
        assertTrue("附言要带钟点：${parcel.note}", parcel.note.contains("18:00") || parcel.note.contains("18点"))
        assertTrue("广告不相关：${inbox.decided[2L to 1L]}", inbox.decided[2L to 1L]!!.startsWith("不相关"))
        assertEquals("ledger", inbox.handoffs.single { it.noticeId == 3L }.to)
        assertEquals("reminder", inbox.handoffs.single { it.noticeId == 4L }.to)

        val plans = mutableListOf<ReminderPlan>()
        val agent = ReminderNoticeAgent.build { plan, _ -> plans += plan; 2001L }
        val input = ReminderNoticePrompt.input("短信", "菜鸟驿站", "【菜鸟驿站】您的包裹已到幸福里驿站，凭取件码 3-2-1088 领取", redacted = false, note = parcel.note)
        val run = AgentRuntime.runOnce(agent, Input(input, now), AgentRuntime.memory)
        println("提醒子代理：$run\n建的：$plans")
        val plan = plans.singleOrNull()
        assertNotNull("应该恰好建一条：$plans", plan)
        assertFalse("说了钟点，不是当天事项：$plan", plan!!.allDay)
        assertEquals("18:00 提醒：$plan", 18, java.time.OffsetDateTime.parse(plan.firstTriggerAt).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).hour)
        assertTrue("标题带取件码：${plan.title}", plan.title.contains("3-2-1088"))
        assertFalse("附言没说轻提醒：$plan", plan.gentle)
    }

    /** 整理员（§6.9）：看一段对话，交上记忆和摘要；账目、一次性的事不进记忆，摘要不写「已经办好」 */
    @Test
    fun `tidy extracts memories and writes a summary`() = runBlocking {
        val book = FakeMemoryBook("他在备考 CPA")
        install(book)
        val at = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1).withHour(21).withMinute(3)
        val turns = listOf(
            Turn(1, listOf(
                Item.UserMessage("我一般九点到公司，晚上七点下班。明早八点提醒我带伞", at),
                Item.ToolCall("c1", "create_reminder", "{\"title\":\"带伞\"}"),
                Item.ToolResult("c1", "已建好：明天 08:00 带伞", ok = true),
                Item.AssistantMessage("好了，明天 08:00 提醒你带伞。")
            )),
            Turn(2, listOf(
                Item.UserMessage("考试改到 12 月了。以后说老地方就是公司楼下那家瑞幸", at.plusMinutes(2)),
                Item.AssistantMessage("知道了。")
            )),
            Turn(3, listOf(
                Item.UserMessage("刚才午饭 36.5 是和同事吃的", at.plusMinutes(5)),
                Item.ToolCall("c2", "update_expenses", "{}"),
                Item.ToolResult("c2", "改好了 1 笔：午饭 36.50 → 餐饮/堂食", ok = true),
                Item.AssistantMessage("改好了。")
            ))
        )
        val input = TidyText.input(book.list.toList(), null, turns, compact = true, stale = emptySet())
        println(input)
        val result = AgentRuntime.runOnce(TidyAgent.build(book, compact = true, remember = true), Input(input, ZonedDateTime.now()))
        val s = result.payloads<SaveTidyTool.Saved>().lastOrNull()
        assertNotNull("没交作业：$result", s)
        println("记忆：${book.list.map { "m${it.id} ${it.text}" }}\n摘要：${s!!.summary}")
        assertTrue("没记下作息或老地方", book.list.any { "瑞幸" in it.text || "九点" in it.text || "9" in it.text })
        assertTrue("考试改期没更新到 m1", book.list.none { it.text == "他在备考 CPA" })
        assertFalse("账进了记忆", book.list.any { "36" in it.text })
        assertNotNull(s.summary)
    }

    /**
     * 随话发图（§6.10）：网关认不认 `input_image`、这个模型看不看得了图。
     * 现画一张写着数字的图发过去，问它图上写的什么 —— 认不出来、或者被网关退回来，这一条就红。
     */
    @Test
    fun `the gateway takes a picture and the model reads it`() = runBlocking {
        val number = "4729"
        val file = File.createTempFile("daodian-probe", ".png").apply { deleteOnExit() }
        file.writeBytes(ProbeImage.digits(number))

        val book = FakeMemoryBook()
        install(book)
        val input = Input("图上写的是什么数字？只回数字", ZonedDateTime.now(), images = listOf(file.path))
        val events = AgentRuntime.run(assistant(book), Session(), input).onEach(::log).toList()
        val end = events.last()
        assertTrue("带图的请求没走通（${profile.model}）：$end", end is AgentEvent.Finished)
        val reply = (end as AgentEvent.Finished).turn.items.filterIsInstance<Item.AssistantMessage>().joinToString { it.text }
        println("模型看到的：$reply")
        assertTrue("没认出图上的 $number：$reply", number in reply)
    }

    private fun log(e: AgentEvent) {
        when (e) {
            is AgentEvent.Model -> when (val m = e.event) {
                is LlmEvent.ToolStarted -> println("  [${e.step}] 调工具 ${m.name}")
                is LlmEvent.FellBack -> println("  [${e.step}] 流式没走通，回退一次性")
                is LlmEvent.Done -> println("  [${e.step}] 完成：text=${m.output.text.take(80)} calls=${m.output.toolCalls}")
                else -> Unit
            }
            is AgentEvent.ToolFinished -> println("  工具结果：${e.outcome.output}")
            else -> println("  $e")
        }
    }
}
