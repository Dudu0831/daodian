package com.abc.daodian.card

import com.abc.daodian.card.assistant.CardDesk
import com.abc.daodian.card.assistant.CardGuide
import com.abc.daodian.card.assistant.CardTools
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.source.TimeSource
import com.abc.daodian.card.spec.Block
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpec
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref
import com.abc.daodian.card.spec.count
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

/** 内存里的卡片，测试用。能绑的是真的提醒、记账交的名字 + 时间 */
class FakeCardDesk : CardDesk {

    val cards = mutableListOf<Card>()

    override val vocabulary = CardSpecs.Vocabulary(listOf(ReminderCards, LedgerCards, TimeSource { LocalDate.parse("2026-11-15") }))

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
}

/** 卡片说明的闸门（五条）、时间、两个工具、给模型的样板（DESIGN §8.5） */
class CardTest {

    private val desk = FakeCardDesk()
    private val vocab = desk.vocabulary
    private fun bad(json: String): List<String> = (CardSpecs.check(json, vocab) as CardSpecs.Result.Bad).problems
    private fun card(vararg blocks: String, size: String = "2x2") = """{"name":"x","size":"$size","body":{"column":[${blocks.joinToString(",")}]}}"""

    private val countdown = CardGuide.SAMPLES.first().second

    // ---- 绑定

    @Test
    fun `a ref reads with or without braces, holes come out of text`() {
        assertEquals(Ref("reminder", "days_left", listOf("#7")), Ref.parse("{{ reminder.days_left #7 }}"))
        assertEquals(Ref("reminder", "check", listOf("#12", "3")), Ref.parse("reminder.check #12 3"))
        assertNull(Ref.parse("cups"))
        assertNull(Ref.parse("{{a { b}}"))
        assertEquals(listOf("reminder.date #12"), Ref.holes("{{reminder.date #12}} 出发"))
        assertEquals(Ref("ledger", "spent", listOf("week")), Ref.whole(" {{ledger.spent week}} "))
        assertNull(Ref.whole("{{ledger.spent week}} 元"))
        assertEquals("还剩 5 样", runBlocking { Ref.fill("还剩 {{reminder.items_left #12}} 样") { "5" } })
    }

    // ---- 闸门

    @Test
    fun `every sample in the guide passes the gate against the real sources`() {
        CardGuide.SAMPLES.forEach { (what, json) ->
            val r = CardSpecs.check(json, vocab)
            assertTrue("$what：$r", r is CardSpecs.Result.Ok)
        }
        val spec = (CardSpecs.check(countdown, vocab) as CardSpecs.Result.Ok).spec
        assertEquals("去旧金山", spec.name)
        assertEquals(CardSize.S2X2, spec.size)
        assertTrue((spec.body as Block.Column).children[1].let { it is Block.Number && it.grow })
        val luggage = (CardSpecs.check(CardGuide.SAMPLES[2].second, vocab) as CardSpecs.Result.Ok).spec
        val right = (luggage.body as Block.Row).children[1] as Block.Column
        assertEquals(Ref("reminder", "items", listOf("#12")), (right.children[1] as Block.Checklist).rows)
    }

    @Test
    fun `rule 1, number-like blocks take only a binding`() {
        val day = """{"text":"{{reminder.title #12}}"}"""
        assertTrue(bad(card(day, """{"number":"37"}""")).any { "只能是一个名字" in it })
        assertTrue(bad(card(day, """{"number":"还剩 {{reminder.days_left #12}}"}""")).any { "只能是一个名字" in it })
        assertTrue(bad(card(day, """{"bar":"50"}""")).any { "只能是一个名字" in it })
        assertTrue(bad(card(day, """{"dots":"3","of":8}""")).any { "只能是一个名字" in it })
        assertTrue(bad(card("""{"checklist":"{{reminder.today}}"}""")).any { "是一组行，这里要一组带方框的行" in it })
        assertTrue(bad(card("""{"timer":"{{reminder.time #15}}"}""")).any { "是字，这里要时刻" in it })
    }

    @Test
    fun `rule 2, a card binds at least one of your records`() {
        val year = card("""{"text":"今年","style":"title"}""", """{"number":"{{time.year_left}}","after":"天"}""")
        assertTrue(bad(year).any { "至少要绑一条提醒或记账里的记录" in it })
        assertTrue(bad(card("""{"text":"春节"}""")).any { "至少要绑一条" in it })
        // 只有一颗按钮也算绑了
        assertTrue(CardSpecs.check(card("""{"text":"喝药"}""", """{"button":"完成","do":"reminder.done #7"}"""), vocab) is CardSpecs.Result.Ok)
    }

    @Test
    fun `days to a written date and the card's own numbers are gone`() {
        assertTrue(bad(card("""{"number":"{{time.days_until 2027-02-06}}"}""")).any { "time 没有 days_until" in it })
        assertTrue(bad("""{"name":"喝水","size":"2x2","own":{"cups":{"count":0}},"body":{"column":[{"number":"{{reminder.today_count}}"}]}}""")
            .any { "卡片不自己记数" in it })
        assertTrue(bad(card("""{"number":"{{own.cups}}"}""")).any { "没有 own 这一类" in it })
        assertTrue(bad(card("""{"text":"x"}""", """{"button":"＋1","do":"own.add cups 1"}""")).any { "没有 own 这一类" in it })
    }

    @Test
    fun `rule 4, buttons take only actions a module offers, with the right arguments`() {
        val t = """{"text":"{{reminder.title #7}}"}"""
        assertTrue(bad(card(t, """{"button":"好","do":"reminder.fly #7"}""")).any { "没有 fly 这个动作" in it })
        assertTrue(bad(card(t, """{"button":"好","do":"reminder.done"}""")).any { "参数是「#编号」" in it })
        assertTrue(bad(card(t, """{"button":"","do":"reminder.done #7"}""")).any { "要写字" in it })
    }

    @Test
    fun `rule 5, each size holds only so many blocks`() {
        val t = """{"text":"{{reminder.title #7}}"}"""
        assertEquals(listOf(8, 6, 14, 24), CardSize.entries.map { it.maxBlocks })
        val seven = Array(7) { t }
        assertTrue(CardSpecs.check(card(*seven), vocab) is CardSpecs.Result.Ok)
        assertTrue(bad(card(*seven, t)).any { "2×2 最多 8 个" in it && "现在 9 个" in it })
        assertTrue(CardSpecs.check(card(*seven, t, size = "4x2"), vocab) is CardSpecs.Result.Ok)
        assertTrue(bad(card(*Array(6) { t }, size = "4x1")).any { "4×1 最多 6 个" in it })
        CardGuide.SAMPLES.forEach { (what, json) ->
            val spec = CardSpecs.read(json)!!
            assertTrue(what, spec.body.count() <= spec.size.maxBlocks)
        }
    }

    @Test
    fun `the gate says what to fix in words the model can act on`() {
        val t = """{"text":"{{reminder.title #7}}"}"""
        assertTrue(bad("""{"name":"x","size":"3x3","body":{"column":[$t]}}""").any { "size 只能是" in it })
        assertTrue(bad(card(t, """{"circle":1}""")).any { "认不出" in it })
        assertTrue(bad(card("""{"text":"{{reminder.nope #7}}"}""")).any { "reminder 没有 nope" in it })
        assertTrue(bad(card(t, """{"number":"{{reminder.today}}"}""")).any { "是一组行，这里要数 或 字" in it })
        assertTrue(bad(card("""{"number":"{{reminder.days_left}}"}""")).any { "参数是「#编号」" in it })
        assertTrue(bad(card(t, """{"text":"x","accent_when":"{{reminder.title #7}}"}""")).any { "这里要是 / 否" in it })
        assertTrue(bad("""{"name":"x","size":"2x2","body":$t}""").any { "最外面要是 column 或 row" in it })
        assertTrue(CardSpecs.check("不是 json", vocab) is CardSpecs.Result.Bad)
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
    private fun spec(json: String) = CardTools.mapper.writeValueAsString(json)

    @Test
    fun `the kit has two tools, no way to change numbers or pin from the model`() {
        assertEquals(setOf("design_card", "list_cards"), tools.keys)
        assertEquals(CardTools.NAMES, tools.keys)
    }

    @Test
    fun `designing a card stores it and the gate turns a bad one back to the model`() {
        val ok = call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(countdown)}}""")
        assertTrue(ok.output, ok.ok)
        assertEquals(1L, ok.ref)
        assertTrue(ok.output.startsWith("画好了：c1「去旧金山」（2×2）"))

        val no = call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(card("""{"number":"37"}"""))}}""")
        assertFalse(no.ok)
        assertTrue(no.output.contains("只能是一个名字") && no.output.contains("不用问用户"))
        assertEquals(1, desk.cards.size)
    }

    @Test
    fun `a new size for a card on the desktop makes a new card`() {
        call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(countdown)}}""")
        val again = call(CardTools.DESIGN, """{"card_id":1,"spec":${spec(countdown.replace("\"去旧金山\",\"size\"", "\"旧金山\",\"size\""))}}""")
        assertTrue(again.ok)
        assertEquals("旧金山", desk.cards.single().name)

        desk.cards[0] = desk.cards[0].copy(widgetId = 42)
        val wide = call(CardTools.DESIGN, """{"card_id":1,"spec":${spec(countdown.replace("\"2x2\"", "\"4x2\""))}}""")
        assertTrue(wide.output, wide.ok && wide.output.contains("另画了这张 c2"))
        assertEquals(2, desk.cards.size)
        assertEquals("2x2", desk.cards[0].size)
    }

    @Test
    fun `listing shows the spec to edit from`() {
        assertEquals("还没画过卡片。", call(CardTools.LIST, "{}").output)
        call(CardTools.DESIGN, """{"card_id":null,"spec":${spec(countdown)}}""")
        val out = call(CardTools.LIST, "{}").output
        assertTrue(out, out.startsWith("c1「去旧金山」2×2 · 还没放上桌面\n说明：{"))
    }

    @Test
    fun `the guide lists what each source offers and has no own or water`() {
        val guide = CardTools.kit(desk).guide
        assertTrue(guide.contains("{{reminder.days_left #编号}} 数 · 离那天还有几天"))
        assertTrue(guide.contains("{{reminder.items #编号}} 一组带方框的行"))
        assertTrue(guide.contains("{{reminder.fire_at #编号}} 时刻"))
        assertTrue(guide.contains("reminder.check #编号 第几项"))
        assertTrue(guide.contains("{{ledger.by_category today|week|month|last_week|last_month}}"))
        assertTrue(guide.contains("{{time.weekday}}"))
        assertTrue(guide.contains("2x2（最多 8 个零件）"))
        assertFalse(guide.contains("own"))
        assertFalse(guide.contains("喝水\""))
    }
}
