package com.abc.daodian.card

import com.abc.daodian.card.assistant.CardDesk
import com.abc.daodian.card.assistant.CardTools
import com.abc.daodian.card.data.Card
import com.abc.daodian.card.plugin.CardSubject
import com.abc.daodian.runtime.tool.ToolContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存里的桌面卡片，测试用。[subjects] 是「提醒」这一种里有哪几件；[supported] = 桌面收不收「加一块」的请求 */
class FakeCardDesk(private val subjects: Map<Long, CardSubject> = emptyMap(), private val supported: Boolean = true) : CardDesk {

    val cards = mutableListOf<Card>()

    override val sources = listOf(CardDesk.Source("reminder", "提醒", "ref 是提醒的 # 编号，用 list_reminders 查。"))

    override suspend fun subject(source: String, ref: Long): CardSubject? = subjects[ref].takeIf { source == "reminder" }

    override suspend fun cards(): List<Card> = cards.toList()

    override suspend fun pin(source: String, ref: Long, template: CardTemplate, start: LocalDate?): CardDesk.Placing {
        val card = Card(
            id = cards.size + 1L, source = source, ref = ref, template = template.name,
            startDay = (start ?: LocalDate.parse("2026-11-13")).toString(), createdAt = 0
        )
        cards += card
        return CardDesk.Placing(card, supported)
    }

    override suspend fun retarget(cardId: Long, source: String, ref: Long, start: LocalDate?): Card? {
        val i = cards.indexOfFirst { it.id == cardId }.takeIf { it >= 0 } ?: return null
        cards[i] = cards[i].copy(source = source, ref = ref, startDay = (start ?: LocalDate.parse("2026-11-13")).toString())
        return cards[i]
    }
}

/** 桌面卡片：卡片上的字是代码算的（DESIGN §8.5），和它的三个工具。这里不认识提醒：钉的那件事只是「叫什么、哪一天、办完没有」 */
class CardTest {

    private fun day(s: String) = LocalDate.parse(s)

    private val milk = CardSubject("牛奶过期", day("2027-02-13"))

    private fun card(start: String = "2026-11-13", template: CardTemplate = CardTemplate.BIG) =
        Card(id = 1, source = "reminder", ref = 7, template = template.name, startDay = start, createdAt = 0)

    private fun face(today: String, s: CardSubject = milk, c: Card = card()) = CardFace.of(s, c, day(today))

    @Test
    fun `far away it counts the days and names the date with its year`() {
        val f = face("2026-11-13")
        assertEquals("92", f.big)
        assertEquals("天", f.after)
        assertEquals("", f.before)
        assertFalse(f.word)
        assertEquals("2027 年 2 月 13 日", f.foot)
        assertEquals("还剩 92 天", f.left)
        assertEquals(CardFace.Tone.NORMAL, f.tone)
    }

    @Test
    fun `the number changes by itself as days pass, with no one touching the card`() {
        assertEquals("91", face("2026-11-14").big)
        assertEquals("2 月 13 日", face("2027-01-01").foot)
    }

    @Test
    fun `the last week turns near and shows the weekday`() {
        val f = face("2027-02-08")
        assertEquals("5", f.big)
        assertEquals("周六", f.foot)
        assertEquals(CardFace.Tone.NEAR, f.tone)
        assertEquals(CardFace.Tone.NEAR, face("2027-02-06").tone)
        assertEquals(CardFace.Tone.NORMAL, face("2027-02-05").tone)
    }

    @Test
    fun `on the day it says today, after it counts how long past`() {
        val today = face("2027-02-13")
        assertEquals("今天", today.big)
        assertTrue(today.word)
        assertEquals("", today.after)
        assertEquals("2 月 13 日", today.foot)

        val past = face("2027-02-15")
        assertEquals("过", past.before)
        assertEquals("2", past.big)
        assertEquals("过了 2 天", past.left)
        assertEquals(CardFace.Tone.GONE, past.tone)
    }

    @Test
    fun `a finished one says so whatever the date`() {
        val f = face("2027-01-01", s = milk.copy(done = true))
        assertEquals("办完了", f.big)
        assertTrue(f.word)
        assertEquals(100, f.progress)
        assertEquals(CardFace.Tone.GONE, f.tone)
    }

    @Test
    fun `the bar walks from its start to the day`() {
        assertEquals(0, face("2026-11-13").progress)
        assertEquals(50, face("2026-12-29").progress)
        assertEquals(100, face("2027-03-01").progress)
        val lease = face("2026-11-13", c = card(start = "2025-12-30", template = CardTemplate.BAR))
        assertEquals("2025 年 12 月 30 日", lease.startLabel)
        assertEquals("2027 年 2 月 13 日", lease.endLabel)
        assertTrue(lease.progress in 70..85)
        // 起点写在那天以后：当没写，从今天算
        assertEquals(0, face("2026-11-13", c = card(start = "2028-01-01")).progress)
    }

    // ---- 工具

    private val ctx = ToolContext(ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai")), "把牛奶过期放到桌面")
    private fun tools(desk: FakeCardDesk) = CardTools.kit(desk).tools.associateBy { it.name }

    @Test
    fun `pinning prepares a card and tells the model what the user has to do next`() = runBlocking {
        val desk = FakeCardDesk(mapOf(7L to milk))
        val out = tools(desk).getValue(CardTools.PIN).execute("""{"source":"reminder","ref":7,"template":"big","start":null}""", ctx)
        assertTrue(out.output, out.ok)
        assertEquals(7L, out.ref)
        assertTrue(out.output.startsWith("备好了：大数字 · 牛奶过期"))
        assertTrue(out.output.contains("点「添加」"))
        assertEquals(CardTemplate.BIG.name, desk.cards.single().template)

        // 桌面不收请求：改说怎么手动加
        val manual = tools(FakeCardDesk(mapOf(7L to milk), supported = false))
            .getValue(CardTools.PIN).execute("""{"source":"reminder","ref":7,"template":"bar","start":"2026-01-01"}""", ctx)
        assertTrue(manual.output.contains("长按桌面") && manual.output.contains("墨条"))
    }

    @Test
    fun `pinning refuses what is not there, a source it does not have, a template it does not know`() = runBlocking {
        val pin = tools(FakeCardDesk(mapOf(7L to milk))).getValue(CardTools.PIN)
        val gone = pin.execute("""{"source":"reminder","ref":9,"template":"big","start":null}""", ctx)
        assertTrue(!gone.ok && gone.output.contains("提醒里没有 #9"))
        val alien = pin.execute("""{"source":"ledger","ref":7,"template":"big","start":null}""", ctx)
        assertTrue(!alien.ok && alien.output.contains("source 只能是 reminder"))
        assertFalse(pin.execute("""{"source":"reminder","ref":7,"template":"round","start":null}""", ctx).ok)
    }

    @Test
    fun `listing and changing go by the card number`() = runBlocking {
        val desk = FakeCardDesk(mapOf(7L to milk, 9L to CardSubject("春节", day("2027-02-06"))))
        val t = tools(desk)
        assertEquals("桌面上还没有钉卡片。", t.getValue(CardTools.LIST).execute("{}", ctx).output)
        t.getValue(CardTools.PIN).execute("""{"source":"reminder","ref":7,"template":"big","start":null}""", ctx)
        assertEquals(
            "c1 · 大数字 · 提醒 #7 牛奶过期（2027-02-13，还剩 92 天） · 备好了，还没放上桌面",
            t.getValue(CardTools.LIST).execute("{}", ctx).output
        )

        val changed = t.getValue(CardTools.CHANGE).execute("""{"card_id":1,"source":"reminder","ref":9,"start":null}""", ctx)
        assertTrue(changed.output, changed.ok && changed.output.contains("#9 春节") && changed.output.contains("还剩 85 天"))
        assertFalse(t.getValue(CardTools.CHANGE).execute("""{"card_id":5,"source":"reminder","ref":9,"start":null}""", ctx).ok)
    }

    @Test
    fun `the guide carries what each source says about itself`() {
        val guide = CardTools.kit(FakeCardDesk()).guide
        assertTrue(guide.contains("· reminder（提醒）：ref 是提醒的 # 编号，用 list_reminders 查。"))
    }
}
