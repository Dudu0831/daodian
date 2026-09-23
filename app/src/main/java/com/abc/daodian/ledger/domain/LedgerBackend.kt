package com.abc.daodian.ledger.domain

/**
 * 工具眼里的账本。ledger/ 包用 Room 实现它，单测用内存假货。
 *
 * 分工：工具负责「读懂参数 + 过护栏」，这里负责「原子地写进去」。
 * 所有写方法都是**一次一个事务**：一批里有一笔写不进，整批不留痕迹。
 */
interface LedgerBackend {

    // ---------------- 读 ----------------

    suspend fun raws(ids: Collection<Long>): List<RawNote>

    suspend fun categories(): List<CategoryNode>

    suspend fun txns(ids: Collection<Long>): List<TxnBrief>

    /** 这些原始通知已经挂在哪笔流水上（作废的不算）。rawId → 流水 */
    suspend fun txnsOfRaws(rawIds: Collection<Long>): Map<Long, TxnBrief>

    suspend fun query(q: ExpenseQuery): List<TxnBrief>

    // ---------------- 写 ----------------

    /**
     * 整理 agent 的产出落库：新建或就地更新流水、标忽略 / 看不清。
     * 已经过了护栏；这里只管写。返回每笔草稿对应的流水 id（和 [drafts] 一一对应）。
     */
    suspend fun record(
        drafts: List<ExpenseDraft>,
        ignored: List<RawVerdict>,
        unreadable: List<RawVerdict>,
        actor: Actor,
        parsedBy: String
    ): List<Long>

    /** 改已有的流水。已经过了护栏 */
    suspend fun update(changes: List<TxnChange>, actor: Actor): Unit

    /** 新建一个类别，返回 id */
    suspend fun addCategory(name: String, parentId: Long?, kind: CategoryKind, actor: Actor): Long
}

/**
 * 一笔待落库的流水（过完护栏的样子）。
 * [rawIds] 为空 = 用户在对话里随口说的（[TxnSource.CHAT]）。
 */
data class ExpenseDraft(
    val rawIds: List<Long>,
    val direction: Direction,
    val amount: Long,
    val occurredAt: Long,
    val timeBasis: TimeBasis,
    val account: AccountRef?,
    val channel: String?,
    val merchantRaw: String?,
    val merchant: String?,
    val summary: String,
    val note: String?,
    /** 解析好的类别 id；null = 未归类。要新建的二级类别在 [newSubcategory] */
    val categoryId: Long?,
    val newSubcategory: NewSubcategory?,
    val tags: List<String>,
    val confidence: Double?,
    /** 非 null = 待确认，这是想问用户的话 */
    val ask: String?,
    val refundOf: Long?,
    /** 就地更新的那笔（重跑整理时）；null = 新建 */
    val replaces: Long?,
    val confirmed: Boolean = false
)

/** 草稿里要顺手建的二级类别：挂在 [parentId] 下 */
data class NewSubcategory(val parentId: Long, val name: String)

data class AccountRef(val bank: String, val tail: String?, val type: AccountType)

/** 一条原始通知的判决：不是账（忽略）/ 看不清。[reason] 给人看 */
data class RawVerdict(val rawId: Long, val reason: String)

/** 对一笔已有流水的改动。null 字段 = 不动 */
data class TxnChange(
    val txnId: Long,
    /** 整笔改归一个类别（清掉原来的分摊） */
    val categoryId: Long? = null,
    val newSubcategory: NewSubcategory? = null,
    /** 拆成几类。和 [categoryId] 二选一 */
    val split: List<Pair<Long?, Long>>? = null,
    val summary: String? = null,
    val note: String? = null,
    val addTags: List<String> = emptyList(),
    val merchant: String? = null,
    /** 记进商户记忆：以后这家默认归这类。只有用户确认过的才会走到这里 */
    val rememberMerchant: Boolean = false,
    val confirm: Boolean = false,
    val voidReason: String? = null,
    val refundOf: Long? = null,
    val reason: String? = null
)

/** 查账条件。全是 null = 最近的 [limit] 笔 */
data class ExpenseQuery(
    val fromDay: Int? = null,
    val toDay: Int? = null,
    /** 类别 id（一级的话连同它下面的二级）；[UNCATEGORIZED] = 未归类 */
    val categoryId: Long? = null,
    val direction: Direction? = null,
    val state: TxnState? = null,
    val keyword: String? = null,
    val tag: String? = null,
    val ids: List<Long>? = null,
    val limit: Int = 50,
    val includeVoid: Boolean = false
) {
    companion object {
        /** [categoryId] 填它 = 查「未归类」的 */
        const val UNCATEGORIZED = -1L
    }
}
