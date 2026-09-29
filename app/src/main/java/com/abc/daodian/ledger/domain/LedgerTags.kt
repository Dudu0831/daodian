package com.abc.daodian.ledger.domain

/*
 * 标签的两件纯逻辑：挑哪几个当候选、打字时找到什么。账单页和对账问卡共用（DESIGN.md §10.4「标签怎么打」）。
 * 标签多了也不全摆出来：候选只放一行，其余靠打字找。
 */

/** 一个标签用得怎么样。[recent] 是最近 [LedgerTags.RECENT_DAYS] 天挂了几笔，[lastDay] 是最后一笔的 yyyyMMdd */
data class TagUse(val name: String, val count: Int, val recent: Int, val lastDay: Int?)

/** 一笔流水打标签时要的全部：挂着的、候选（排好序的）、全部标签（打字找用，用得多的在前） */
data class TagBoard(
    val attached: List<String>,
    val ranked: List<String>,
    val all: List<String>
)

object LedgerTags {

    /** 「最近用得多」看多少天 */
    const val RECENT_DAYS = 30L

    /** 候选最多给几个；界面一行放得下几个画几个 */
    const val MAX_RANKED = 12

    /** 打字找时最多列几个 */
    const val MAX_MATCHES = 6

    /** 新标签名最长几个字 */
    const val MAX_NAME = 12

    /** 全部标签的顺序：最近用得多的在前，再按总笔数、最后一次 */
    fun byUse(uses: List<TagUse>): List<String> =
        uses.sortedWith(
            compareByDescending<TagUse> { it.recent }.thenByDescending { it.count }.thenByDescending { it.lastDay ?: 0 }
        ).map { it.name }

    /**
     * 候选：同一天别的几笔刚打的（今晚第一笔打了「约会」，后面几笔它排第一）→ 这家商户以前打的 →
     * 最近用得多的。挂着的也在里面：排得上的留在原位（界面上点了取下不会跳），排不上的放最前。
     */
    fun rank(attached: List<String>, sameDay: List<String>, sameMerchant: List<String>, uses: List<TagUse>): List<String> {
        val order = LinkedHashSet<String>()
        (sameDay + sameMerchant).forEach(order::add)
        byUse(uses.filter { it.recent > 0 }).forEach(order::add)
        val kept = order.take(MAX_RANKED)
        return attached.filter { it !in kept } + kept
    }

    /** 打字找的结果 */
    sealed interface Found {
        /** 还没打字：给候选 */
        data object Nothing : Found

        /** 名字里有这几个字的；[create] 非空 = 没有一模一样的，可以新建 */
        data class Matches(val names: List<String>, val create: String?) : Found

        /** 打的字把一个已有的标签包在里面（「约会花销」↔「约会」）：多半就是它 */
        data class Near(val existing: String, val typed: String) : Found

        /** 和一个类别同名：那是分类，不是标签（§10.4） */
        data class Category(val path: String, val typed: String) : Found
    }

    /**
     * 打了 [query] 找到什么。[all] 按 [byUse] 的顺序；比较不分大小写，# 开头的去掉。
     * 找得到一模一样的标签就是它，其次才看撞不撞类别、像不像已有的。
     */
    fun find(query: String, all: List<String>, categories: List<CategoryNode>): Found {
        val q = clean(query)
        if (q.isEmpty()) return Found.Nothing
        val exact = all.firstOrNull { it.equals(q, ignoreCase = true) }
        if (exact == null) {
            categories.firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { c ->
                return Found.Category(LedgerText.path(c.id, categories)?.replace("/", " › ") ?: c.name, q)
            }
        }
        val matches = all.filter { it.contains(q, ignoreCase = true) }
            .sortedByDescending { it.equals(q, ignoreCase = true) }
            .take(MAX_MATCHES)
        if (matches.isEmpty()) {
            all.firstOrNull { q.contains(it, ignoreCase = true) }?.let { return Found.Near(it, q) }
        }
        return Found.Matches(matches, create = if (exact == null) q else null)
    }

    /** 标签名：去空白、去开头的 #，截到 [MAX_NAME] 字 */
    fun clean(name: String): String = name.trim().removePrefix("#").trim().take(MAX_NAME)
}
