package com.abc.daodian.card

import android.content.Context
import com.abc.daodian.card.assistant.CardDesk
import com.abc.daodian.card.assistant.CardGuide
import com.abc.daodian.card.assistant.CardTools
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.own.OwnData
import com.abc.daodian.card.plugin.CardField
import com.abc.daodian.card.plugin.CardKind
import com.abc.daodian.card.plugin.CardSource
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.source.TimeSource
import com.abc.daodian.card.spec.Block
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref
import com.abc.daodian.runtime.tool.ToolContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试用的「提醒」来源：只有名字，不碰库 */
object FakeReminderSource : CardSource {
    override val id = "reminder"
    override val label = "提醒"
    override val brief = "#编号用 list_reminders 查。"
    override val fields = listOf(
        CardField("title", CardKind.TEXT, "标题", "#编号"),
        CardField("date", CardKind.TEXT, "哪天", "#编号"),
        CardField("days_left", CardKind.NUMBER, "还剩几天", "#编号"),
        CardField("near", CardKind.FLAG, "最后 7 天", "#编号"),
        CardField("past", CardKind.FLAG, "过了没", "#编号"),
        CardField("today", CardKind.ROWS, "今天的几件")
    )
    override val actions = listOf(CardField("done", CardKind.ACTION, "点完成", "#编号"))
    override suspend fun read(context: Context, name: String, args: List<String>): CardValue? = null
}

/** 内存里的卡片，测试用。[supported] = 桌面收不收「加一块」的请求 */
class FakeCardDesk(private val supported: Boolean = true) : CardDesk {

    val cards = mutableListOf<Card>()

    override val vocabulary = CardSpecs.Vocabulary(listOf(FakeReminderSource, TimeSource { LocalDate.parse("2026-11-15") }))

    override suspend fun cards(): List<Card> = cards.toList()

    override suspend fun create(raw: String, spec: CardSpec): Card {
        val c = Card(id = cards.size + 1L, name = spec.name, size = spec.size.key, spec = raw, createdAt = 0, updatedAt = 0)
        cards += c
        return c
    }

    override suspend fun redraw(id: Long, raw: String, spec: CardSpec): Card? {
        val i = cards.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        cards[i] = cards[i].copy(name = spec.name, size = spec.size.key, spec = raw)
        return cards[i]
    }

    override suspend fun pin(id: Long): Cards.Pinned? {
        val c = cards.firstOrNull { it.id == id } ?: return null
        return if (c.widgetId != null) Cards.Pinned(c, asked = false, already = true) else Cards.Pinned(c, supported)
    }

    override suspend fun changeOwn(id: Long, changes: List<Triple<String, String, String?>>): Pair<Card, List<String>>? {
        val i = cards.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val spec = CardSpecs.read(cards[i].spec)!!
        val own = OwnData(spec.own, cards[i].own, LocalDate.parse("2026-11-15"), 0)
        val problems = changes.mapNotNull { (k, op, v) -> own.apply(op, k, v)?.let { "$k：$it" } }
        cards[i] = cards[i].copy(own = own.json())
        return cards[i] to problems
    }
}

/** 卡片说明的闸门、卡片自己记的数、四个工具（DESIGN §8.5） */
class CardTest {

    private val desk = FakeCardDesk()
    private val vocab = desk.vocabulary
    private fun bad(json: String): List<String> = (CardSpecs.check(json, vocab) as CardSpecs.Result.Bad).problems

    // ---- 绑定

    @Test
    fun `a ref reads with or without braces, holes come out of text`() {
        assertEquals(Ref("reminder", "days_left", listOf("#7")), Ref.parse("{{ reminder.days_left #7 }}"))
        assertEquals(Ref("own", "cups.left"), Ref.parse("own.cups.left"))
        assertNull(Ref.parse("cups"))
        assertNull(Ref.parse("{{a + b}}".replace("+", "{")))
        assertEquals(listOf("own.cups.left"), Ref.holes("还差 {{own.cups.left}} 杯"))
        assertEquals(Ref("own", "cups"), Ref.whole(" {{own.cups}} "))
        assertNull(Ref.whole("{{own.cups}} 杯"))
        assertEquals("还差 5 杯", runBlocking { Ref.fill("还差 {{own.cups.left}} 杯") { "5" } })
    }

    // ---- 闸门

    @Test
    fun `the two examples in the guide pass the gate`() {
        listOf(CardGuide.WATER, CardGuide.COUNTDOWN).forEach { json ->
            val r = CardSpecs.check(json, vocab)
            assertTrue("$r", r is CardSpecs.Result.Ok)
        }
        val water = (CardSpecs.check(CardGuide.WATER, vocab) as CardSpecs.Result.Ok).spec
        assertEquals("喝水", water.name)
        assertEquals(CardSize.S2X2, water.size)
        val body = water.body as Block.Column
        assertTrue(body.children[1] is Block.Number && body.children[1].grow)
        assertEquals(Ref("own", "add", listOf("cups", "1")), ((body.children[3] as Block.Row).children[1] as Block.Button).action)
    }

    @Test
    fun `the gate says what to fix in words the model can act on`() {
        assertTrue(bad("""{"name":"x","size":"3x3","body":{"column":[]}}""").any { "size 只能是" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"circle":1}]}}""").any { "认不出" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"text":"{{ledger.spent week}}"}]}}""").any { "没有 ledger 这一类" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"text":"{{reminder.nope #7}}"}]}}""").any { "reminder 没有 nope" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"number":"{{reminder.today}}"}]}}""").any { "是一组行，这里要数 或 字" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"number":"{{reminder.days_left}}"}]}}""").any { "参数是「#编号」" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"number":"{{own.cups}}"}]}}""").any { "own 里没有 cups" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"button":"＋1","do":"own.add cups"}]}}""").any { "own 里没有 cups" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"button":"好","do":"reminder.fly #7"}]}}""").any { "没有 fly 这个动作" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[{"text":"x","accent_when":"{{reminder.title #7}}"}]}}""").any { "这里要是 / 否" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"text":"光一行"}}""").any { "最外面要是 column 或 row" in it })
        val many = (1..30).joinToString(",") { """{"rule":true}""" }
        assertTrue(bad("""{"name":"x","size":"2x2","body":{"column":[$many]}}""").any { "零件太多了" in it })
        assertTrue(CardSpecs.check("不是 json", vocab) is CardSpecs.Result.Bad)
    }

    // ---- 卡片自己记的

    private val today = LocalDate.parse("2026-11-15")

    @Test
    fun `a count stops at its max, and a daily one starts over the next day`() {
        val defs = mapOf("cups" to com.abc.daodian.card.own.OwnDef.Count(0, 8, daily = true))
        val own = OwnData(defs, null, today, 0)
        repeat(10) { assertNull(own.apply("add", "cups", null)) }
        assertEquals(CardValue.Number(8), own.read("cups"))
        assertEquals(CardValue.Flag(true), own.read("cups.full"))
        assertEquals(CardValue.Number(0), own.read("cups.left"))
        // 第二天读出来是 0，不用等零点那一刻有人来
        val tomorrow = OwnData(defs, own.json(), today.plusDays(1), 0)
        assertEquals(CardValue.Number(0), tomorrow.read("cups"))
        assertEquals(CardValue.Number(8), OwnData(defs, own.json(), today, 0).read("cups"))
    }

    @Test
    fun `a checklist toggles, grows, shrinks, and its rows carry their own toggle`() {
        val defs = mapOf("items" to com.abc.daodian.card.own.OwnDef.Checklist(listOf("鸡蛋", "番茄")))
        val own = OwnData(defs, null, today, 0)
        assertNull(own.apply("toggle", "items", "1"))
        assertNull(own.apply("add_item", "items", "葱"))
        assertNull(own.apply("remove_item", "items", "2"))
        val rows = own.read("items") as CardValue.Rows
        assertEquals(listOf("鸡蛋" to true, "葱" to false), rows.rows.map { it.title to it.done })
        assertEquals("own.toggle items 2", rows.rows[1].action)
        assertEquals(CardValue.Number(1), own.read("items.left"))
        assertTrue(own.apply("toggle", "items", "9")!!.contains("现在有 2 项"))
        assertTrue(own.apply("add", "items", "1")!!.contains("只能 toggle"))
    }

    @Test
    fun `a day of its own counts like a reminder would`() {
        val defs = mapOf("spring" to com.abc.daodian.card.own.OwnDef.Day(LocalDate.parse("2027-02-06")))
        val own = OwnData(defs, null, today, 0)
        assertEquals(CardValue.Number(83), own.read("spring.days_left"))
        assertEquals(CardValue.Text("2027 年 2 月 6 日"), own.read("spring"))
        assertEquals(CardValue.Flag(false), own.read("spring.near"))
        assertNull(own.apply("set", "spring", "2026-11-20"))
        assertEquals(CardValue.Flag(true), own.read("spring.near"))
    }

    @Test
    fun `a timer runs until its end`() {
        val defs = mapOf("noodle" to com.abc.daodian.card.own.OwnDef.Timer(180))
        val own = OwnData(defs, null, today, 1_000_000)
        assertEquals(CardValue.Text("3:00"), own.read("noodle"))
        assertNull(own.apply("start", "noodle", null))
        assertEquals(1_180_000L, own.until("noodle"))
        assertEquals(CardValue.Flag(true), own.read("noodle.running"))
        assertEquals(CardValue.Flag(false), OwnData(defs, own.json(), today, 1_200_000).read("noodle.running"))
    }

    @Test
    fun `time counts days and progress`() {
        val t = TimeSource { today }
        assertEquals(CardValue.Text("周日"), t.read("weekday", emptyList()))
        assertEquals(CardValue.Number(83), t.read("days_until", listOf("2027-02-06")))
        assertEquals(CardValue.Number(50), t.read("progress", listOf("2026-11-05", "2026-11-25")))
        assertEquals(CardValue.Number(46), t.read("year_left", emptyList()))
        assertNull(t.read("days_until", listOf("明年")))
    }

    // ---- 工具

    private val ctx = ToolContext(ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai")), "桌面放一个喝水的")
    private val tools = CardTools.kit(desk).tools.associateBy { it.name }
    private fun call(tool: String, args: String) = runBlocking { tools.getValue(tool).execute(args, ctx) }
    private fun spec(json: String) = CardTools.mapper.writeValueAsString(json)

    @Test
    fun `designing a card stores it and the gate turns a bad one back to the model`() {
        val ok = call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(CardGuide.WATER)}}""")
        assertTrue(ok.output, ok.ok)
        assertEquals(1L, ok.ref)
        assertTrue(ok.output.startsWith("画好了：c1「喝水」（2×2）"))

        val no = call(CardTools.DESIGN, """{"card_id":null,"spec":${spec("""{"name":"x","size":"2x2","body":{"column":[{"number":"{{own.cups}}"}]}}""")}}""")
        assertFalse(no.ok)
        assertTrue(no.output.contains("own 里没有 cups") && no.output.contains("不用问用户"))
        assertEquals(1, desk.cards.size)
    }

    @Test
    fun `redrawing keeps the numbers, and a new size for a card on the desktop makes a new card`() {
        call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(CardGuide.WATER)}}""")
        call(CardTools.CHANGE, """{"card_id":1,"changes":[{"key":"cups","op":"add","value":"3"}]}""")
        val again = call(CardTools.DESIGN, """{"card_id":1,"spec":${spec(CardGuide.WATER.replace("\"喝水\",\"size\"", "\"喝水啊\",\"size\""))}}""")
        assertTrue(again.ok)
        assertEquals("喝水啊", desk.cards.single().name)
        assertTrue(CardTools.ownSummary(desk.cards.single(), ctx.now).contains("cups 3"))

        desk.cards[0] = desk.cards[0].copy(widgetId = 42)
        val wide = call(CardTools.DESIGN, """{"card_id":1,"spec":${spec(CardGuide.WATER.replace("\"2x2\"", "\"4x2\""))}}""")
        assertTrue(wide.output, wide.ok && wide.output.contains("另画了这张 c2"))
        assertEquals(2, desk.cards.size)
        assertEquals("2x2", desk.cards[0].size)
    }

    @Test
    fun `pinning tells the model what the user has to do next`() {
        call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(CardGuide.WATER)}}""")
        assertTrue(call(CardTools.PIN, """{"card_id":1}""").output.contains("点「添加」"))
        val manual = runBlocking { CardTools.kit(FakeCardDesk(supported = false).also { it.create(CardGuide.WATER, CardSpecs.read(CardGuide.WATER)!!) }).tools.first { it.name == CardTools.PIN }.execute("""{"card_id":1}""", ctx) }
        assertTrue(manual.output.contains("长按桌面") && manual.output.contains("卡片 2×2"))
        desk.cards[0] = desk.cards[0].copy(widgetId = 7)
        assertTrue(call(CardTools.PIN, """{"card_id":1}""").output.contains("已经在桌面上了"))
        assertFalse(call(CardTools.PIN, """{"card_id":9}""").ok)
    }

    @Test
    fun `listing shows the spec to edit from and what the card remembers`() {
        assertEquals("还没画过卡片。", call(CardTools.LIST, "{}").output)
        call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(CardGuide.WATER)}}""")
        val out = call(CardTools.LIST, "{}").output
        assertTrue(out, out.startsWith("c1「喝水」2×2 · 还没放上桌面\n记着：cups 0\n说明：{"))
    }

    @Test
    fun `changing the numbers reports what did not take`() {
        call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(CardGuide.WATER)}}""")
        val out = call(CardTools.CHANGE, """{"card_id":1,"changes":[{"key":"cups","op":"add","value":"2"},{"key":"tea","op":"add","value":null}]}""")
        assertTrue(out.output, out.ok && out.output.contains("cups 2") && out.output.contains("没有叫 tea 的"))
        assertFalse(call(CardTools.CHANGE, """{"card_id":1,"changes":[{"key":"tea","op":"add","value":null}]}""").ok)
    }

    @Test
    fun `the guide lists what each source offers`() {
        val guide = CardTools.kit(desk).guide
        assertTrue(guide.contains("{{reminder.days_left #编号}} 数 · 还剩几天"))
        assertTrue(guide.contains("reminder.done #编号 · 点完成"))
        assertTrue(guide.contains("{{time.weekday}}"))
    }
}
