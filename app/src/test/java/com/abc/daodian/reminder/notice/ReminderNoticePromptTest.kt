package com.abc.daodian.reminder.notice

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderNoticePromptTest {

    @Test
    fun `a notice and the note read as one input`() {
        assertEquals(
            "通知：短信 · 菜鸟驿站\n正文：取件码 3-2-1088\n附言：18:00 提醒取快递，标题带 3-2-1088",
            ReminderNoticePrompt.input("短信", "菜鸟驿站", "取件码 3-2-1088", redacted = false, note = "18:00 提醒取快递，标题带 3-2-1088")
        )
    }

    @Test
    fun `hidden text and an empty note are said out loud`() {
        assertEquals(
            "通知：招商银行\n正文：（被系统遮蔽了，看不清）\n附言：（没写）",
            ReminderNoticePrompt.input("招商银行", null, "敏感数据已隐藏", redacted = true, note = " ")
        )
    }
}
