package com.abc.daodian.harness.builtin.ledger

import java.time.ZoneId

/** 流水、原始通知写成模型读的一行字。工具结果和整理 agent 的输入共用这一份写法 */
object LedgerText {

    /** `#12 2026-09-22 19:20 支出 319.40 日用/超市日用 · 山姆会员商店 · 招商银行 8837 信用卡 · 支付宝 「山姆买了一周的日用」 [自动归的]` */
    fun txn(t: TxnBrief, zone: ZoneId): String = buildString {
        append("#${t.id} ${LedgerJson.stamp(t.occurredAt, zone)} ${t.direction.label} ${Money.yuan(t.amount)}")
        append(" ").append(t.category ?: "未归类")
        listOfNotNull(t.merchant ?: t.merchantRaw, t.account, t.channel).forEach { append(" · ").append(it) }
        append(" 「").append(t.summary).append("」")
        append(" [").append(t.state.label).append("]")
        if (t.source == TxnSource.CHAT) append("[对话里记的]")
        t.refundOf?.let { append(" 退的是 #$it") }
        if (t.tags.isNotEmpty()) append(" 标签:").append(t.tags.joinToString(","))
        t.note?.let { append(" 备注:").append(it) }
        t.ask?.let { append(" 待问:").append(it) }
        if (t.rawIds.isNotEmpty()) append(" 来自 raw#").append(t.rawIds.joinToString(",raw#"))
    }

    /** `raw#17 [2026-09-22 19:20 掌上生活] 交易提醒｜您在支付宝-山姆会员商店有一笔…` */
    fun raw(r: RawNote, zone: ZoneId): String = buildString {
        append("raw#${r.id} [${LedgerJson.stamp(r.postTime, zone)} ${r.source}] ")
        append(r.title ?: "（无标题）").append("｜").append(r.text ?: "（无正文）")
        r.extra?.let { append("｜").append(it) }
        if (r.redacted) append(" ⚠正文被系统遮蔽")
    }

    /** 一批流水的合计：支出、退款、收入分开写 */
    fun totals(list: List<TxnBrief>): String {
        val live = list.filter { it.state != TxnState.VOID }
        fun sum(d: Direction) = live.filter { it.direction == d }.sumOf { it.amount }
        val out = sum(Direction.OUT)
        val refund = sum(Direction.REFUND)
        return "共 ${live.size} 笔：支出 ${Money.yuan(out)}，退款 ${Money.yuan(refund)}（净支出 ${Money.yuan(out - refund)}），" +
            "收入 ${Money.yuan(sum(Direction.IN))}，转移 ${Money.yuan(sum(Direction.TRANSFER))}"
    }

    fun categoryTree(categories: List<CategoryNode>): String = buildString {
        for (kind in CategoryKind.entries) {
            append(if (kind == CategoryKind.OUT) "支出类别：" else "收入类别：")
            val tops = categories.filter { it.isTop && it.kind == kind }
            append(tops.joinToString("；") { top ->
                val kids = categories.filter { it.parentId == top.id }
                if (kids.isEmpty()) top.name else "${top.name}（${kids.joinToString("、") { it.name }}）"
            })
            append("\n")
        }
    }.trimEnd()

    /** 类别 id → 「日用/理发」 */
    fun path(id: Long?, categories: List<CategoryNode>): String? {
        val c = categories.firstOrNull { it.id == id } ?: return null
        val parent = c.parentId?.let { p -> categories.firstOrNull { it.id == p } }
        return if (parent == null) c.name else "${parent.name}/${c.name}"
    }
}
