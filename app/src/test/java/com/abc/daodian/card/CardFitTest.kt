package com.abc.daodian.card

import com.abc.daodian.card.assistant.CardGuide
import com.abc.daodian.card.render.CardFit
import com.abc.daodian.card.render.Space
import com.abc.daodian.card.spec.Block
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.NumberSize
import com.abc.daodian.card.spec.Ref
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 照实际大小收放（§8.5「跟着大小变」）：列表几行、数字多大、放不下先收哪块 */
class CardFitTest {

    /** 绑定换成像样的值；清单 10 样、今天 5 件 */
    private val look = object : CardFit.Look {
        override suspend fun text(template: String) = Ref.fill(template) { r ->
            when (r?.name) {
                "days_left" -> "35"
                "date" -> "12 月 23 日"
                "weekday" -> "周三"
                "title" -> "收拾行李（飞旧金山）"
                else -> "8"
            }
        }
        override suspend fun rows(ref: Ref) = if (ref.name == "items") 10 else 5
    }

    private fun sample(name: String) = CardSpecs.read(CardGuide.SAMPLES.first { it.first.startsWith(name) }.second)!!.body
    /** 标准尺寸按 4×2（352×164）算：高过 197 才算拖大了 */
    private fun fit(body: Block, w: Int, h: Int, standard: Space = Space(352f, 164f)) = runBlocking { CardFit.fit(body, Space(w.toFloat(), h.toFloat()), look, standard) }

    private fun Block.all(): List<Block> = listOf(this) + when (this) {
        is Block.Column -> children.flatMap { it.all() }
        is Block.Row -> children.flatMap { it.all() }
        else -> emptyList()
    }

    private fun Block.number() = all().filterIsInstance<Block.Number>().single()
    private fun Block.checklist() = all().filterIsInstance<Block.Checklist>().single()

    @Test
    fun `a checklist shows more rows when the card is taller, up to what the spec allows`() {
        // 样板里写的 max 4：4×2 放 4 行，拖成 4×4 接着往下列，10 样全列出来
        val luggage = sample("倒数 + 行李")
        assertEquals(4, fit(luggage, 352, 164).checklist().max)
        assertEquals(4, fit(luggage, 352, 183).checklist().max)
        assertEquals(10, fit(luggage, 352, 400).checklist().max)
        // 模型写了 max 10：矮的少放，高的多放
        val roomy = CardSpecs.read(CardGuide.SAMPLES.first { it.first.startsWith("倒数 + 行李") }.second.replace("\"max\":4", "\"max\":10"))!!.body
        val short = fit(roomy, 352, 130).checklist().max
        val tall = fit(roomy, 352, 352).checklist().max
        assertTrue("$short < $tall", short < tall)
        assertTrue(tall >= 10 || tall * 26 + 14 + 15 > 352 - 25 - 26)
    }

    @Test
    fun `the big number grows with the card and stays within the spec's size`() {
        val countdown = sample("倒数")
        // 说明写的 l：标准 2×2 放得下 l，压扁了退到小号
        assertEquals(NumberSize.L, fit(countdown, 164, 164, Space(164f, 164f)).number().size)
        val squat = fit(countdown, 164, 110, Space(164f, 164f)).number().size!!
        assertTrue("$squat", squat < NumberSize.L)
        // 拖大了有富余，超过说明写的，长到 xl
        val free = CardSpecs.read(CardGuide.SAMPLES.first().second.replace(",\"size\":\"l\"", ""))!!.body
        assertEquals(NumberSize.XL, fit(free, 352, 352).number().size)
        // 没拖大（荣耀上 4×2 实际 183）：照说明，不超过 l
        assertEquals(NumberSize.L, fit(free, 352, 183).number().size)
    }

    @Test
    fun `when it does not fit, small print goes first and the number stays`() {
        val countdown = sample("倒数")
        // 拖成 4×1 那么矮：先收掉日期那行小字，再收标题，数字留着
        val strip = fit(countdown, 352, 72) as Block.Column
        assertEquals(1, strip.children.size)
        assertTrue(strip.children.single() is Block.Number)
        // 稍高一点：标题留下，小字收掉
        val mid = fit(countdown, 352, 90) as Block.Column
        assertTrue(mid.children.none { it is Block.Text && it.style == com.abc.daodian.card.spec.TextStyle.CAPTION })
        assertTrue(mid.children.any { it is Block.Number })
    }

    @Test
    fun `a number beside text in a row keeps the size the spec asked for and nothing is dropped at its own size`() {
        val rent = sample("重复的")
        val fitted = fit(rent, 352, 72)
        assertEquals(NumberSize.S, fitted.number().size)
        assertEquals(rent.all().size, fitted.all().size)
    }

    @Test
    fun `every sample keeps all its parts at its own size`() {
        CardGuide.SAMPLES.forEach { (what, json) ->
            val spec = CardSpecs.read(json)!!
            val fitted = fit(spec.body, spec.size.widthDp, spec.size.heightDp, Space(spec.size.widthDp.toFloat(), spec.size.heightDp.toFloat()))
            assertEquals(what, spec.body.all().size, fitted.all().size)
        }
    }
}
