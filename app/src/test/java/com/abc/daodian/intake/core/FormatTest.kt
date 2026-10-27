package com.abc.daodian.intake.core

import com.abc.daodian.intake.core.format.ChatFormat
import com.abc.daodian.intake.core.format.Formats
import com.abc.daodian.intake.core.format.PlainFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FormatTest {

    private fun chat(title: String?, text: String?) = Formats.of(WECHAT).normalize(notice(WECHAT, title, text))

    @Test
    fun `chat apps get the unread counts and the repeated name stripped`() {
        assertEquals("老婆", chat("老婆(3条)", "x").title)
        assertEquals("小美", chat("小美（2条新消息）", "x").title)
        assertEquals("晚上带瓶酱油", chat("老婆(3条)", "[3条]老婆: 晚上带瓶酱油").body)
        assertEquals("到点 买菜", chat("老婆", "老婆：到点 买菜").body)
        assertEquals("晚上带瓶酱油", chat("老婆", "晚上带瓶酱油").body)
    }

    @Test
    fun `in a group the sender stays, that is who said it`() {
        assertEquals("张三: 明天开会", chat("家庭群", "[2条]张三: 明天开会").body)
    }

    @Test
    fun `nothing left after stripping is null`() {
        assertNull(chat("老婆", "[2条]老婆: ").body)
        assertNull(chat("  ", null).title)
    }

    @Test
    fun `everything else is only trimmed`() {
        assertSame(PlainFormat, Formats.of(SMS))
        assertSame(ChatFormat, Formats.of(WECHAT))
        val n = Formats.of(SMS).normalize(notice(SMS, " 菜鸟驿站(2条) ", " 取件码 3-2-1088 "))
        assertEquals("菜鸟驿站(2条)", n.title)
        assertEquals("取件码 3-2-1088", n.body)
    }

    @Test
    fun `the expanded part counts as text`() {
        val n = PlainFormat.normalize(notice(CMB, "招商银行", "您的账户支出", extra = "  明细：午饭 58.00  "))
        assertEquals("您的账户支出\n明细：午饭 58.00", n.fullText)
        assertNull(PlainFormat.normalize(notice(CMB, "招商银行", null)).fullText)
    }
}
