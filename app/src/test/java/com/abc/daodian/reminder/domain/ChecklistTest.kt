package com.abc.daodian.reminder.domain

import com.abc.daodian.reminder.data.ReminderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecklistTest {

    private fun items(vararg pairs: Pair<String, Boolean>) =
        pairs.mapIndexed { i, (t, d) -> ReminderItem(id = i + 1L, reminderId = 1, text = t, done = d, position = i) }

    @Test
    fun `notification texts`() {
        val some = items("护照" to false, "插头" to true, "外套" to false, "雨伞" to false, "药" to false, "墨镜" to false)
        assertEquals("还剩 5 样：护照、外套、雨伞、药、墨镜", Checklist.remaining(some))
        assertEquals("还剩 5 样：护照、外套、雨伞、药 等", Checklist.remaining(some, atMost = 4))
        assertEquals("清单还剩 5 样", Checklist.count(some))
        assertEquals("清单都勾完了", Checklist.remaining(items("护照" to true)))
        assertNull(Checklist.remaining(emptyList()))
        assertNull(Checklist.count(emptyList()))
    }

    @Test
    fun `clean trims and caps the length`() {
        assertNull(Checklist.clean("   "))
        assertEquals("一二三四五六七八九十一二三四五六七八九十", Checklist.clean(" 一二三四五六七八九十一二三四五六七八九十多出来 "))
    }

    @Test
    fun `adding stops at the limit and the rest is reported`() {
        val full = items(*Array(29) { "东西$it" to false })
        val plan = ChecklistEdit(add = listOf("a", "b", "c"), remove = listOf(1)).plan(full)
        assertEquals(listOf("a", "b"), plan.added)
        assertEquals(listOf("c"), plan.dropped)
    }
}
