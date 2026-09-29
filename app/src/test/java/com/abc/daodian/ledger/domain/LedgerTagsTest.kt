package com.abc.daodian.ledger.domain

import com.abc.daodian.ledger.domain.LedgerTags.Found
import org.junit.Assert.assertEquals
import org.junit.Test

/** 标签多了怎么办：候选挑哪几个、打字找到什么 */
class LedgerTagsTest {

    private val categories = listOf(
        CategoryNode(1, "餐饮", null, CategoryKind.OUT),
        CategoryNode(2, "外卖", 1, CategoryKind.OUT),
    )

    private val uses = listOf(
        TagUse("报销", count = 3, recent = 3, lastDay = 20260920),
        TagUse("约会", count = 12, recent = 9, lastDay = 20260927),
        TagUse("大理旅行", count = 20, recent = 0, lastDay = 20250501),
        TagUse("请客", count = 4, recent = 4, lastDay = 20260926),
        TagUse("日本旅行", count = 8, recent = 0, lastDay = 20240301),
    )

    @Test
    fun `candidates put the same day first, then this merchant, then recent use`() {
        val all = LedgerTags.byUse(uses)
        assertEquals(listOf("约会", "请客", "报销", "大理旅行", "日本旅行"), all)

        // 今晚别的几笔打了「出差」，这家以前打过「报销」；很久没用的旅行不进候选
        val ranked = LedgerTags.rank(emptyList(), sameDay = listOf("出差"), sameMerchant = listOf("报销"), uses = uses)
        assertEquals(listOf("出差", "报销", "约会", "请客"), ranked)

        // 挂着的排得上就留在原位，排不上的放最前
        assertEquals(listOf("大理旅行", "出差", "报销", "约会", "请客"), LedgerTags.rank(listOf("大理旅行", "约会"), listOf("出差"), listOf("报销"), uses))
    }

    @Test
    fun `typing finds, warns about near names and categories, or offers a new one`() {
        val all = LedgerTags.byUse(uses)
        assertEquals(Found.Nothing, LedgerTags.find("  ", all, categories))
        assertEquals(Found.Matches(listOf("大理旅行", "日本旅行"), create = "旅行"), LedgerTags.find("旅行", all, categories))
        assertEquals(Found.Matches(listOf("约会"), create = null), LedgerTags.find("#约会", all, categories))
        assertEquals(Found.Near("约会", "约会花销"), LedgerTags.find("约会花销", all, categories))
        assertEquals(Found.Category("餐饮 › 外卖", "外卖"), LedgerTags.find("外卖", all, categories))
        assertEquals(Found.Matches(emptyList(), create = "出差"), LedgerTags.find("出差", all, categories))
    }
}
