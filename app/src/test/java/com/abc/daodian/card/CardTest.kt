package com.abc.daodian.card

import com.abc.daodian.card.assistant.CardDesk
import com.abc.daodian.card.assistant.CardSamples
import com.abc.daodian.card.assistant.CardTools
import com.abc.daodian.card.assistant.CardTrial
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.plugin.CardRow
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.render.CardPage
import com.abc.daodian.card.source.TimeSource
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref
import com.abc.daodian.ledger.card.LedgerCards
import com.abc.daodian.reminder.card.ReminderCards
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

/** 内存里的卡片，测试用。能绑的是真的提醒、记账交的名字 + 时间；截图换成 [trialProblems] */
class FakeCardDesk : CardDesk {

    val cards = mutableListOf<Card>()

    var trialProblems: List<String> = emptyList()

    override val vocabulary = CardSpecs.Vocabulary(listOf(ReminderCards, LedgerCards, TimeSource { LocalDate.parse("2026-11-15") }))

    override suspend fun cards(): List<Card> = cards.toList()

    override suspend fun trial(spec: CardSpec) = CardTrial(trialProblems, CardSpecs.refs(spec.html).map { "$it = 35" })

    override suspend fun create(spec: CardSpec): Card {
        val c = Card(id = cards.size + 1L, name = spec.name, size = spec.size.key, spec = CardSpecs.write(spec), createdAt = 0, updatedAt = 0)
        cards += c
        return c
    }

    override suspend fun redraw(id: Long, spec: CardSpec): Card? {
        val i = cards.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        cards[i] = cards[i].copy(name = spec.name, size = spec.size.key, spec = CardSpecs.write(spec))
        return cards[i]
    }
}

/** 卡片：网页里的绑定、闸门、拼页、时间、两个工具、给模型的样板（DESIGN §8.5） */
class CardTest {

    private val desk = FakeCardDesk()
    private val vocab = desk.vocabulary
    private fun check(html: String, size: String = "2x2", name: String = "x") = CardSpecs.check(name, size, html, vocab)
    private fun bad(html: String, size: String = "2x2", name: String = "x"): List<String> = (check(html, size, name) as CardSpecs.Result.Bad).problems

    private val luggage = CardSamples.ALL.first()

    // ---- 绑定

    @Test
    fun `a ref reads with or without braces, holes come out of text`() {
        assertEquals(Ref("reminder", "days_left", listOf("#7")), Ref.parse("{{ reminder.days_left #7 }}"))
        assertEquals(Ref("reminder", "check", listOf("#12", "3")), Ref.parse("reminder.check #12 3"))
        assertNull(Ref.parse("cups"))
        assertNull(Ref.parse("{{a { b}}"))
        assertEquals(listOf("reminder.date #12"), Ref.holes("{{reminder.date #12}} 出发"))
        assertEquals("还剩 5 样", runBlocking { Ref.fill("还剩 {{reminder.items_left #12}} 样") { "5" } })
    }

    @Test
    fun `refs come from text holes and D lookups, taps from data-tap`() {
        val html = """<b>{{reminder.days_left #7}}</b><script>var a=D["reminder.items #7"],b=D[ 'ledger.daily week' ];</script>
            <div data-tap="reminder.done #7"></div><div data-tap="{{x}}"></div>"""
        assertEquals(listOf("reminder.days_left #7", "reminder.items #7", "ledger.daily week"), CardSpecs.refs(html).map { it.toString() })
        assertEquals(listOf("reminder.done #7"), CardSpecs.taps(html))
    }

    // ---- 闸门

    @Test
    fun `every sample in the guide passes the gate against the real sources`() {
        CardSamples.ALL.forEach { s ->
            val r = check(s.html, s.size)
            assertTrue("${s.what}：$r", r is CardSpecs.Result.Ok)
        }
        val spec = (check(luggage.html, luggage.size, "去旧金山") as CardSpecs.Result.Ok).spec
        assertEquals("去旧金山", spec.name)
        assertEquals(CardSize.S4X2, spec.size)
        assertEquals(spec, CardSpecs.read(CardSpecs.write(spec)))
    }

    @Test
    fun `a card binds at least one of your records`() {
        assertTrue(bad("""<b>{{time.year_left}}</b>天""").any { "至少要绑一条提醒或记账里的记录" in it })
        assertTrue(bad("<b>春节</b>").any { "至少要绑一条" in it })
        // 只有一块能点完成的也算绑了
        assertTrue(check("""<div data-tap="reminder.done #7">喝药</div>""") is CardSpecs.Result.Ok)
    }

    @Test
    fun `the gate names what to fix in words the model can act on`() {
        val t = "{{reminder.title #7}}"
        assertTrue(bad(t, size = "3x3").any { "size 只能是" in it })
        assertTrue(bad(t, name = " ").any { "name 要写" in it })
        assertTrue(bad("").any { "html 要写" in it })
        assertTrue(bad("{{reminder.nope #7}}").any { "reminder 没有 nope" in it })
        assertTrue(bad("{{reminder.days_left}}").any { "参数是「#编号」" in it })
        assertTrue(bad("{{own.cups}}").any { "没有 own 这一类" in it })
        assertTrue(bad("{{time.days_until 2027-02-06}}").any { "time 没有 days_until" in it })
        assertTrue(bad("""$t<div data-tap="reminder.fly #7"></div>""").any { "没有 fly 这个动作" in it })
        assertTrue(bad("""$t<div data-tap="reminder.done"></div>""").any { "参数是「#编号」" in it })
        assertTrue(bad("<html><body>$t</body></html>").any { "不要写 <html>" in it })
        assertTrue(bad("""$t<img src="https://x.com/a.png">""").any { "拉不进来" in it })
        assertTrue(bad("""$t<script src="a.js"></script>""").any { "拉不进来" in it })
        assertTrue(bad(t + "x".repeat(CardSpecs.MAX_HTML)).any { "网页太长了" in it })
        assertNull(CardSpecs.read("""{"name":"x","size":"2x2","body":{"column":[]}}"""))
    }

    // ---- 拼页

    @Test
    fun `the page fills holes as escaped text and D as json`() = runBlocking {
        val values = mapOf(
            "reminder.title #7" to CardValue.Text("<去>旧金山"),
            "reminder.days_left #7" to CardValue.Number(35),
            "reminder.items #7" to CardValue.Rows(listOf(CardRow("护照", action = "reminder.check #7 1"), CardRow("耳机", done = true)), total = 5),
        )
        val page = CardPage.build(
            """<b>{{reminder.title #7}}</b>{{reminder.days_left #7}}{{reminder.near #7}}<script>var it=D["reminder.items #7"];</script>""",
            352f, 164f, CardPage.DARK
        ) { values[it.toString()] }
        assertTrue(page.contains("<b>&lt;去&gt;旧金山</b>35—"))
        assertTrue(page, page.contains(""""reminder.items #7":{"rows":[{"title":"护照","note":"","accent":false,"done":false,"tap":"reminder.check #7 1","value":null},{"title":"耳机","note":"","accent":false,"done":true,"tap":null,"value":null}],"total":5}"""))
        assertTrue(page.contains("--accent:#C4604F") && page.contains("width:352px;height:164px"))
        assertTrue(page.contains("default-src 'none'"))
    }

    @Test
    fun `data that says close-script cannot break out of the script`() = runBlocking {
        val page = CardPage.build("""<script>D["reminder.title #7"]</script>""", 164f, 164f, CardPage.LIGHT) { CardValue.Text("</script><b>") }
        assertFalse(page.contains("\"</script><b>\""))
        assertTrue(page.contains("<\\/script>"))
    }

    // ---- 时间

    @Test
    fun `time only knows the calendar`() {
        val t = TimeSource { LocalDate.parse("2026-11-15") }
        assertEquals(CardValue.Text("周日"), t.read("weekday"))
        assertEquals(CardValue.Text("11 月 15 日"), t.read("date"))
        assertEquals(CardValue.Number(46), t.read("year_left"))
        assertEquals(listOf("date", "weekday", "year_left", "year_progress"), t.fields.map { it.name })
    }

    // ---- 工具

    private val ctx = ToolContext(ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai")), "桌面放一个去旧金山的倒数")
    private val tools = CardTools.kit(desk).tools.associateBy { it.name }
    private fun call(tool: String, args: String) = runBlocking { tools.getValue(tool).execute(args, ctx) }
    private fun design(id: Long?, name: String, size: String, html: String) =
        call(CardTools.DESIGN, CardTools.mapper.writeValueAsString(mapOf("card_id" to id, "name" to name, "size" to size, "html" to html)))

    @Test
    fun `the kit has two tools, no way to change numbers or pin from the model`() {
        assertEquals(setOf("design_card", "list_cards"), tools.keys)
        assertEquals(CardTools.NAMES, tools.keys)
    }

    @Test
    fun `designing a card stores it, tells the bound values, and turns a bad one back`() {
        val ok = design(null, "去旧金山", "4x2", luggage.html)
        assertTrue(ok.output, ok.ok)
        assertEquals(1L, ok.ref)
        assertTrue(ok.output.startsWith("画好了：c1「去旧金山」（4×2）"))
        assertTrue(ok.output, ok.output.contains("- reminder.days_left #12 = 35"))

        val no = design(null, "x", "2x2", "<b>37</b>")
        assertFalse(no.ok)
        assertTrue(no.output.contains("至少要绑一条") && no.output.contains("不用问用户"))

        desk.trialProblems = listOf("超出卡片：高出 30px，超出的部分桌面上看不到")
        val over = design(null, "x", "2x2", "{{reminder.title #7}}")
        assertFalse(over.ok)
        assertTrue(over.output, over.output.contains("照 2×2（164×164px）截了一张") && over.output.contains("高出 30px"))
        assertEquals(1, desk.cards.size)
    }

    @Test
    fun `a new size for a card on the desktop makes a new card`() {
        design(null, "去旧金山", "2x2", "{{reminder.days_left #12}}")
        val again = design(1, "旧金山", "2x2", "{{reminder.days_left #12}}")
        assertTrue(again.ok)
        assertEquals("旧金山", desk.cards.single().name)

        desk.cards[0] = desk.cards[0].copy(widgetId = 42)
        val wide = design(1, "旧金山", "4x2", "{{reminder.days_left #12}}")
        assertTrue(wide.output, wide.ok && wide.output.contains("另画了这张 c2"))
        assertEquals(2, desk.cards.size)
        assertEquals("2x2", desk.cards[0].size)
    }

    @Test
    fun `listing shows the page to edit from`() {
        assertEquals("还没画过卡片。", call(CardTools.LIST, "{}").output)
        design(null, "去旧金山", "2x2", "<b>{{reminder.days_left #12}}</b>")
        val out = call(CardTools.LIST, "{}").output
        assertEquals("c1「去旧金山」2×2 · 还没放上桌面\n网页：\n<b>{{reminder.days_left #12}}</b>", out)
    }

    @Test
    fun `the guide lists what each source offers`() {
        val guide = CardTools.kit(desk).guide
        assertTrue(guide.contains("reminder.days_left #编号 数 · 离那天还有几天"))
        assertTrue(guide.contains("reminder.items #编号 一组带方框的行"))
        assertTrue(guide.contains("reminder.check #编号 第几项"))
        assertTrue(guide.contains("ledger.by_category today|week|month|last_week|last_month"))
        assertTrue(guide.contains("time.weekday"))
        assertTrue(guide.contains("4x2 是 352×164px"))
        assertFalse(guide.contains("own"))
    }
}
