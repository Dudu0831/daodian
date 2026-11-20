package com.abc.daodian.card

import com.abc.daodian.card.assistant.CardSamples
import com.abc.daodian.card.plugin.CardRow
import com.abc.daodian.card.plugin.CardValue
import com.abc.daodian.card.render.CardPage
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpecs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 样板卡（`assistant/CardSamples`）填上一套假数，浅色、深色各拼成一整页，写到 `app/build/card-samples/`，
 * 浏览器打开 `index.html` 就能看（改样板时用；手机上的 WebView 和桌面浏览器差一点，最后还得上模拟器看）
 */
class CardSampleTest {

    private val fake: Map<String, CardValue> = mapOf(
        "reminder.days_left #12" to CardValue.Number(28),
        "reminder.title #12" to CardValue.Text("去新加坡"),
        "reminder.date #12" to CardValue.Text("12 月 18 日"),
        "reminder.weekday #12" to CardValue.Text("周五"),
        "reminder.items_left #12" to CardValue.Number(4),
        "reminder.near #12" to CardValue.Flag(false),
        "reminder.progress #12" to CardValue.Number(62),
        "reminder.items #12" to CardValue.Rows(
            listOf("护照" to true, "转换插头" to false, "降噪耳机" to true, "防晒霜" to false, "薄外套" to false, "充电器" to false)
                .mapIndexed { i, (t, d) -> CardRow(t, done = d, action = "reminder.check #12 ${i + 1}") }
        ),
        "reminder.days_left #20" to CardValue.Number(78),
        "reminder.title #20" to CardValue.Text("春节"),
        "reminder.date #20" to CardValue.Text("2027 年 2 月 6 日"),
        "reminder.weekday #20" to CardValue.Text("周六"),
        "ledger.spent week" to CardValue.Number(558),
        "ledger.count week" to CardValue.Number(6),
        "ledger.spent last_week" to CardValue.Number(1530),
        "ledger.per_day last_week" to CardValue.Number(219),
        "ledger.daily week" to CardValue.Series(listOf(18, 230, 90, 156, 64, 0, 0), listOf("一", "二", "三", "四", "五", "六", "日"), current = 4),
        "ledger.spent month" to CardValue.Number(2846),
        "ledger.count month" to CardValue.Number(41),
        "ledger.per_day month" to CardValue.Number(142),
        "ledger.by_category month" to CardValue.Rows(
            listOf("餐饮" to 1236L, "购物" to 702L, "交通" to 388L, "娱乐" to 290L, "生活缴费" to 160L, "其他" to 70L)
                .map { (t, v) -> CardRow(t, "¥%,d".format(v), value = v) }
        ),
        "time.date" to CardValue.Text("11 月 20 日"),
        "time.weekday" to CardValue.Text("周五"),
        "reminder.upcoming" to CardValue.Rows(
            listOf(Triple("交房租", "明天", 1L), Triple("把资料发给 Kevin", "12月18日", 28L), Triple("去新加坡", "12月18日", 28L), Triple("旧金山出差", "12月23日", 33L), Triple("春节", "2月6日", 78L))
                .mapIndexed { i, (t, n, v) -> CardRow(t, n, action = "reminder.done #${i + 1}", value = v) }
        )
    )

    @Test
    fun `samples bind only what exists and render into pages to look at`() = runBlocking {
        val dir = File("build/card-samples").apply { mkdirs() }
        val cells = StringBuilder()
        CardSamples.ALL.forEachIndexed { i, s ->
            val size = CardSize.of(s.size)!!
            CardSpecs.refs(s.html).forEach { assertTrue("${s.what} 绑的 $it 没有假数", fake.containsKey(it.toString())) }
            for ((tag, palette) in listOf("light" to CardPage.LIGHT, "dark" to CardPage.DARK)) {
                val page = CardPage.build(s.html, size.widthDp.toFloat(), size.heightDp.toFloat(), palette) { fake[it.toString()] }
                File(dir, "s$i-$tag.html").writeText(page)
                cells.append("<figure><iframe srcdoc=\"${CardPage.escape(page)}\" width=\"${size.widthDp}\" height=\"${size.heightDp}\"></iframe><figcaption>$i $tag · ${s.what}</figcaption></figure>\n")
            }
        }
        File(dir, "index.html").writeText(
            "<!doctype html><meta charset=utf-8><style>body{background:#5f7377;font:12px sans-serif;display:flex;flex-wrap:wrap;gap:18px;padding:18px}" +
                "iframe{border:0;border-radius:22px;display:block}figcaption{color:#fff;max-width:352px;margin-top:4px}figure{margin:0}</style>\n$cells"
        )
    }
}
