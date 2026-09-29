package com.abc.daodian.agent.engine.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 问卡的参数怎么读、答案怎么写回去又怎么读回来 */
class AskUserToolTest {

    private val full = """{"label":"4 笔","questions":[
        |{"context":"9月22日 11:02 · 建行","amount":"¥36.50","question":null,"hint":"前两天也是","options":[{"label":"午饭","detail":"餐饮 · 堂食"},{"label":"买菜","detail":null}]},
        |{"context":"9月21日 20:53 · 招行","amount":"¥3.00","question":null,"hint":null,"options":[{"label":"话费充值","detail":null}]}
        |]}""".trimMargin()

    @Test
    fun `draft shows only the questions whose object has closed`() {
        val cut = full.indexOf("¥3.00")
        val draft = AskUserTool.draftOf(full.substring(0, cut))

        assertEquals("4 笔", draft.label)
        assertEquals(1, draft.questions.size)
        assertEquals("¥36.50", draft.questions.single().amount)
        assertEquals(listOf("午饭", "买菜"), draft.questions.single().options.map { it.label })
    }

    @Test
    fun `draft survives braces and quotes inside strings`() {
        val tricky = """{"label":"a","questions":[{"context":"有 } 和 \" 的","amount":null,"question":"q","hint":null,"options":[]},{"cont"""
        val draft = AskUserTool.draftOf(tricky)

        assertEquals(1, draft.questions.size)
        assertEquals("有 } 和 \" 的", draft.questions.single().context)
    }

    @Test
    fun `full request parses and passes the checks`() {
        val r = AskUserTool.requestOf(full)!!
        assertEquals(2, r.questions.size)
        assertNull(AskUserTool.problemOf(r))
    }

    @Test
    fun `picked answers round trip through the output`() {
        val r = AskUserTool.requestOf(full)!!
        val answer = AskAnswer.Picked(listOf(Pick.Typed("给爸买的外套"), null))
        val out = AskUserTool.outputOf(r, answer)

        assertTrue(out, out.contains("¥36.50（9月22日 11:02 · 建行） → 自己写了「给爸买的外套」"))
        assertTrue(out, out.contains("没答，先放着"))
        assertEquals(answer, AskUserTool.answerOf(out))
    }

    @Test
    fun `a question can point at a record, and what was done there rides along`() {
        val withRef = full.replace("\"hint\":\"前两天也是\",", "\"hint\":\"前两天也是\",\"ref\":\"#12\",")
        val r = AskUserTool.requestOf(withRef)!!
        assertEquals("#12", r.questions[0].ref)
        assertNull(r.questions[1].ref)

        val note = AskNote("约会", "这笔挂着标签「约会」，已经挂好了，别再打")
        val answer = AskAnswer.Picked(listOf(Pick.Option(0), null), listOf(note, null))
        val out = AskUserTool.outputOf(r, answer)
        assertTrue(out, out.contains("选了「午饭」（餐饮 · 堂食）；这笔挂着标签「约会」"))
        assertEquals(answer, AskUserTool.answerOf(out))

        val said = AskAnswer.Said("午饭", listOf(note, null))
        val saidOut = AskUserTool.outputOf(r, said)
        assertTrue(saidOut, saidOut.contains("另外第 1 题"))
        assertEquals(said, AskUserTool.answerOf(saidOut))
    }

    @Test
    fun `said and unanswered round trip too`() {
        val said = AskAnswer.Said("午饭，话费")
        assertEquals(said, AskUserTool.answerOf(AskUserTool.outputOf(null, said)))
        assertEquals(AskAnswer.Unanswered, AskUserTool.answerOf(AskUserTool.UNANSWERED))
        assertNull(AskUserTool.answerOf("没问出去：questions 是空的。"))
    }
}
