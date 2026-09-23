package com.abc.daodian.harness.builtin.ledger

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * 记账的领域模型：工具、护栏和存储层都说这一套话。纯 Kotlin，不碰 Android ——
 * 存储由 ledger/ 包实现 [LedgerBackend]，这里的东西都能在 JVM 单测里跑。
 *
 * 口径见 LEDGER_PLAN.md §10：金额一律整数分、永远是正数，正负由 [Direction] 决定。
 */

/** 钱往哪走。统计口径：月支出 = OUT − REFUND，月收入 = IN，TRANSFER 哪里都不算 */
enum class Direction(val label: String) {
    OUT("支出"), IN("收入"), REFUND("退款"), TRANSFER("转移");

    /** 挂哪一边的类别。TRANSFER 不挂类别 */
    val categoryKind: CategoryKind?
        get() = when (this) {
            OUT, REFUND -> CategoryKind.OUT
            IN -> CategoryKind.IN
            TRANSFER -> null
        }
}

enum class CategoryKind { OUT, IN }

/** 流水状态。只往一个方向走：AUTO / PENDING → CONFIRMED；任何状态 → VOID */
enum class TxnState(val label: String) {
    AUTO("自动归的"), PENDING("待确认"), CONFIRMED("你确认过"), VOID("作废");

    /** 重跑整理时能不能动它。确认过的、作废的都不动 */
    val rewritable: Boolean get() = this == AUTO || this == PENDING
}

/** 发生时刻是怎么来的 */
enum class TimeBasis { EXACT, NOTIFIED, DAY }

/** 这笔是从哪来的 */
enum class TxnSource { NOTIFICATION, CHAT }

/** 谁动的手。写进改动历史 */
enum class Actor { MODEL, USER, CODE }

enum class AccountType(val label: String) {
    DEBIT("储蓄卡"), CREDIT("信用卡"), HUABEI("花呗"), BALANCE("余额");

    companion object {
        fun of(name: String?): AccountType? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/** 一条原始通知，给模型看、给护栏对原文用 */
data class RawNote(
    val id: Long,
    /** 哪个 app 发的，人话（「掌上生活」） */
    val source: String,
    val postTime: Long,
    val title: String?,
    val text: String?,
    /** extras 里别的能读的正文（bigText 之类），和 text 重复的已去掉 */
    val extra: String?,
    val redacted: Boolean,
    val done: Boolean,
    /** 整理时被标了「看不清」（正文被遮蔽读不出金额），等用户在对账时说 */
    val unreadable: Boolean = false
) {
    /** 护栏对原文时看的全部文字 */
    val body: String get() = listOfNotNull(title, text, extra).joinToString("\n")
}

data class CategoryNode(val id: Long, val name: String, val parentId: Long?, val kind: CategoryKind) {
    val isTop: Boolean get() = parentId == null
}

/** 类别路径「日用/理发」、「餐饮」 → 两段。空白、全角斜杠都认 */
fun splitCategoryPath(path: String): List<String> =
    path.replace('／', '/').replace('>', '/').replace('›', '/')
        .split('/').map { it.trim() }.filter { it.isNotEmpty() }

/** 一笔流水的摘要：工具回给模型、界面列表、统计明细共用 */
data class TxnBrief(
    val id: Long,
    val direction: Direction,
    val amount: Long,
    val occurredAt: Long,
    val day: Int,
    val summary: String,
    val note: String?,
    /** 「日用/超市日用」；拆成几类就是几段用「+」连；未归类为 null */
    val category: String?,
    val account: String?,
    val channel: String?,
    val merchant: String?,
    val merchantRaw: String?,
    val state: TxnState,
    val source: TxnSource,
    val ask: String?,
    val tags: List<String>,
    val rawIds: List<Long>,
    val refundOf: Long?
)

object Money {

    /** 「319.40」「319.4」「¥1,234」→ 分。认不出、不是正数就是 null */
    fun parseCents(text: String?): Long? = runCatching {
        val clean = text.orEmpty().trim().removePrefix("¥").removePrefix("￥").replace(",", "").removeSuffix("元").trim()
        val v = BigDecimal(clean)
        if (v.signum() <= 0 || v.scale() > 2) return null
        v.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExactOrNull()
    }.getOrNull()

    private fun BigDecimal.longValueExactOrNull(): Long? = runCatching { longValueExact() }.getOrNull()

    /** 分 → 「319.40」 */
    fun yuan(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** 带号的：支出 / 退款 / 收入在列表里的样子 */
    fun signed(direction: Direction, cents: Long): String = when (direction) {
        Direction.IN -> "+${yuan(cents)}"
        Direction.REFUND -> "−${yuan(cents)}"
        else -> yuan(cents)
    }

    /**
     * 金额在原文里的几种写法。银行通知写「319.40」「197.00元」，有的带千分位「1,234.56」，
     * 偶尔省掉末尾的零（「5元」「3.5元」）。
     */
    fun spellings(cents: Long): List<String> {
        val plain = yuan(cents)                                   // 1234.50
        val intPart = cents / 100
        val grouped = "%,d".format(intPart) + "." + plain.substringAfter('.')   // 1,234.50
        val trimmed = BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString()  // 1234.5 / 5
        return listOf(plain, grouped, trimmed).distinct()
    }
}
