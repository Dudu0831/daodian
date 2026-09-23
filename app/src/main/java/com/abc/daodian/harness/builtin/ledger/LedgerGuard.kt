package com.abc.daodian.harness.builtin.ledger

import java.time.Duration

/**
 * 落库前的硬校验（LEDGER_PLAN.md §7 + §10.6）。纯函数，不碰存储 —— 要的数据由工具先查好递进来。
 *
 * 每条规则挡一类模型会犯的错；挡下来的原因写成模型能照着改的话，回给它。
 */
object LedgerGuard {

    /** 每个一级类别下最多几个二级（§10.6 第 13 条） */
    const val MAX_SUB_PER_TOP = 12

    /** 发生时刻离通知时刻最多差多远（§7 第 6 条） */
    val TIME_WINDOW: Duration = Duration.ofHours(24)

    /** 类别解析的结果：已有的 id，或者要顺手建的二级；两者都空 = 未归类 */
    data class CategoryPick(val categoryId: Long?, val newSub: NewSubcategory?)

    sealed interface Check<out T> {
        data class Ok<T>(val value: T) : Check<T>
        data class No(val reason: String) : Check<Nothing>
    }

    /**
     * 把「日用/理发」这样的路径解析成类别。
     *
     * - 一级必须已存在、和方向对得上（支出 / 退款挂支出类别，收入挂收入类别）
     * - 只写一级也行（「其他」「日用」）：就是没细分，统计照样算进这个一级
     * - 二级不存在时，[allowNewSub] 才顺手建（每个一级下封顶 [MAX_SUB_PER_TOP]）；
     *   别的一级下面已经有同名的（「日用/理发」有了还写「其他/理发」）就打回，让它用现成的
     * - 转移不挂类别：给了也忽略
     */
    fun pickCategory(
        path: String?,
        direction: Direction,
        categories: List<CategoryNode>,
        allowNewSub: Boolean
    ): Check<CategoryPick> {
        val kind = direction.categoryKind ?: return Check.Ok(CategoryPick(null, null))
        if (path.isNullOrBlank()) return Check.Ok(CategoryPick(null, null))

        val parts = splitCategoryPath(path)
        if (parts.isEmpty()) return Check.Ok(CategoryPick(null, null))
        if (parts.size > 2) return Check.No("类别只有两层，「$path」多了。写成「一级/二级」。")

        val tops = categories.filter { it.isTop && it.kind == kind }
        val top = tops.firstOrNull { it.name.equals(parts[0], ignoreCase = true) }
            ?: return Check.No(
                "没有叫「${parts[0]}」的${if (kind == CategoryKind.OUT) "支出" else "收入"}一级类别。" +
                    "现有的：${tops.joinToString("、") { it.name }}。一级类别不能自己建。"
            )
        val children = categories.filter { it.parentId == top.id }

        if (parts.size == 1) return Check.Ok(CategoryPick(top.id, null))

        val sub = children.firstOrNull { it.name.equals(parts[1], ignoreCase = true) }
        if (sub != null) return Check.Ok(CategoryPick(sub.id, null))
        if (!allowNewSub) return Check.No("「${top.name}」下面没有「${parts[1]}」。现有的：${children.joinToString("、") { it.name }}。")
        // 别处已经有同名、或名字互相包含的二级（「话费」vs「话费网费」）：用现成的，不另建
        val name = parts[1]
        categories.firstOrNull { c ->
            !c.isTop && c.kind == kind && (c.name.contains(name, ignoreCase = true) || name.contains(c.name, ignoreCase = true))
        }?.let { twin ->
            val owner = categories.first { it.id == twin.parentId }
            return Check.No(
                "已经有「${owner.name}/${twin.name}」了，用它；确实是另一回事就换个更具体的名字。" +
                    "现有类别：${LedgerText.categoryTree(categories)}"
            )
        }
        if (children.size >= MAX_SUB_PER_TOP) {
            return Check.No("「${top.name}」下面已经有 ${children.size} 个二级了，不能再加，从现有的里挑：${children.joinToString("、") { it.name }}。")
        }
        if (parts[1].length > 8) return Check.No("二级类别名「${parts[1]}」太长，8 个字以内。")
        return Check.Ok(CategoryPick(null, NewSubcategory(top.id, parts[1])))
    }

    /** 金额必须能在某条原文里找到（§7 第 1 条）。挡住绝大多数幻觉 */
    fun amountInRaw(cents: Long, raws: List<RawNote>): Boolean {
        val bodies = raws.filterNot { it.redacted }.map { it.body }
        return Money.spellings(cents).any { spelling ->
            val number = "(?<![\\d.,])" + Regex.escape(spelling) + "(?![\\d]|\\.\\d)"
            // 光秃秃的整数（「4918」）太容易撞上卡尾号、日期，必须挨着「元」「¥」「人民币」才算
            val re = if ('.' in spelling) Regex(number)
            else Regex("(?:[¥￥]|人民币)\\s*$number|$number\\s*元")
            bodies.any { re.containsMatchIn(it) }
        }
    }

    /** 发生时刻落在通知时刻前后 24 小时内（§7 第 6 条） */
    fun timeNearRaw(occurredAt: Long, raws: List<RawNote>): Boolean {
        val window = TIME_WINDOW.toMillis()
        return raws.any { kotlin.math.abs(it.postTime - occurredAt) <= window }
    }
}
