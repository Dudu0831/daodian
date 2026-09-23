package com.abc.daodian.ledger

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.abc.daodian.harness.builtin.ledger.AccountRef
import com.abc.daodian.harness.builtin.ledger.Actor
import com.abc.daodian.harness.builtin.ledger.CategoryKind
import com.abc.daodian.harness.builtin.ledger.CategoryNode
import com.abc.daodian.harness.builtin.ledger.ExpenseDraft
import com.abc.daodian.harness.builtin.ledger.ExpenseQuery
import com.abc.daodian.harness.builtin.ledger.LedgerBackend
import com.abc.daodian.harness.builtin.ledger.ListExpensesTool
import com.abc.daodian.harness.builtin.ledger.Money
import com.abc.daodian.harness.builtin.ledger.NewSubcategory
import com.abc.daodian.harness.builtin.ledger.RawNote
import com.abc.daodian.harness.builtin.ledger.RawVerdict
import com.abc.daodian.harness.builtin.ledger.TxnBrief
import com.abc.daodian.harness.builtin.ledger.TxnChange
import com.abc.daodian.harness.builtin.ledger.TxnSource
import com.abc.daodian.harness.builtin.ledger.TxnState
import com.abc.daodian.ledger.db.Account
import com.abc.daodian.ledger.db.Allocation
import com.abc.daodian.ledger.db.Category
import com.abc.daodian.ledger.db.ChangeLog
import com.abc.daodian.ledger.db.LedgerDao
import com.abc.daodian.ledger.db.LedgerDatabase
import com.abc.daodian.ledger.db.Merchant
import com.abc.daodian.ledger.db.MerchantAlias
import com.abc.daodian.ledger.db.RawNotification
import com.abc.daodian.ledger.db.RawState
import com.abc.daodian.ledger.db.Tag
import com.abc.daodian.ledger.db.Txn
import com.abc.daodian.ledger.db.TxnLink
import com.abc.daodian.ledger.db.TxnRaw
import com.abc.daodian.ledger.db.TxnTag
import java.time.Instant
import java.time.ZoneId

/**
 * 账本：[LedgerBackend] 的 Room 实现，外加采集入口（[ingest]）。
 *
 * 所有写都在一个事务里：工具交来的一批，要么全写进去，要么一点不留。
 */
class LedgerStore private constructor(private val db: LedgerDatabase) : LedgerBackend {

    val dao: LedgerDao = db.dao()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    // ---------------- 采集 ----------------

    /**
     * 收一条通知。同一条通知同一段正文只存一次（指纹）。
     *
     * 遮蔽版和真正文的关系在这里就理清，不劳模型：真正文到了，之前的遮蔽版标 SUPERSEDED；
     * 真正文已经在库里了才来的遮蔽版，直接存成 SUPERSEDED。
     */
    suspend fun ingest(
        pkg: String,
        key: String,
        postTime: Long,
        title: String?,
        text: String?,
        extra: String?,
        extras: String,
        how: String,
        capturedAt: Long = System.currentTimeMillis()
    ): Boolean = db.withTransaction {
        val redacted = text?.contains(PaySources.REDACTED) == true
        val state = when {
            title.isNullOrBlank() && text.isNullOrBlank() -> RawState.IGNORED
            redacted && dao.countReal(key, postTime) > 0 -> RawState.SUPERSEDED
            else -> RawState.PENDING
        }
        val id = dao.insertRaw(
            RawNotification(
                pkg = pkg, notifKey = key, postTime = postTime, title = title, text = text, extra = extra,
                extras = extras, capturedAt = capturedAt, capturedHow = how,
                fingerprint = "$key|$postTime|${text.orEmpty()}",
                redacted = redacted, state = state,
                stateNote = if (state == RawState.IGNORED) "空通知（分组汇总）" else null,
                processedAt = if (state == RawState.PENDING) null else capturedAt
            )
        )
        if (id > 0 && !redacted) dao.supersedeRedacted(key, postTime, capturedAt)
        id > 0
    }

    // ---------------- LedgerBackend：读 ----------------

    override suspend fun raws(ids: Collection<Long>): List<RawNote> =
        if (ids.isEmpty()) emptyList() else dao.raws(ids).map(::noteOf)

    fun noteOf(r: RawNotification) = RawNote(
        id = r.id, source = PaySources.nameOf(r.pkg), postTime = r.postTime,
        title = r.title, text = r.text, extra = r.extra, redacted = r.redacted,
        done = r.state != RawState.PENDING,
        unreadable = r.state == RawState.UNREADABLE
    )

    override suspend fun categories(): List<CategoryNode> =
        dao.categories().map { CategoryNode(it.id, it.name, it.parentId, it.kind) }

    override suspend fun txns(ids: Collection<Long>): List<TxnBrief> =
        if (ids.isEmpty()) emptyList() else briefs(dao.txns(ids.distinct()))

    override suspend fun txnsOfRaws(rawIds: Collection<Long>): Map<Long, TxnBrief> {
        if (rawIds.isEmpty()) return emptyMap()
        val owners = dao.ownersOf(rawIds)
        val byId = txns(owners.map { it.txnId }).filter { it.state != TxnState.VOID }.associateBy { it.id }
        return owners.mapNotNull { o -> byId[o.txnId]?.let { o.rawId to it } }.toMap()
    }

    override suspend fun query(q: ExpenseQuery): List<TxnBrief> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!q.includeVoid) where += "t.state != 'VOID'"
        q.fromDay?.let { where += "t.day >= ?"; args += it }
        q.toDay?.let { where += "t.day <= ?"; args += it }
        q.direction?.let { where += "t.direction = ?"; args += it.name }
        q.state?.let { where += "t.state = ?"; args += it.name }
        q.ids?.let { ids -> where += "t.id IN (${ids.joinToString(",") { "?" }})"; args.addAll(ids) }
        when (q.categoryId) {
            null -> Unit
            ListExpensesTool.UNCATEGORIZED ->
                where += "EXISTS (SELECT 1 FROM allocation a WHERE a.txnId = t.id AND a.categoryId IS NULL)"
            else -> {
                where += "EXISTS (SELECT 1 FROM allocation a JOIN category c ON c.id = a.categoryId " +
                    "WHERE a.txnId = t.id AND (c.id = ? OR c.parentId = ?))"
                args += q.categoryId; args += q.categoryId
            }
        }
        q.keyword?.let {
            where += "(t.summary LIKE ? OR IFNULL(t.note, '') LIKE ? OR IFNULL(t.merchantRaw, '') LIKE ? OR " +
                "EXISTS (SELECT 1 FROM merchant m WHERE m.id = t.merchantId AND m.name LIKE ?))"
            repeat(4) { _ -> args += "%$it%" }
        }
        q.tag?.let {
            where += "EXISTS (SELECT 1 FROM txn_tag tt JOIN tag g ON g.id = tt.tagId WHERE tt.txnId = t.id AND g.name = ?)"
            args += it
        }
        val sql = "SELECT t.* FROM txn t" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY t.occurredAt DESC LIMIT ${q.limit}"
        return briefs(dao.queryTxns(SimpleSQLiteQuery(sql, args.toTypedArray())))
    }

    /** 流水 → 带类别路径、账户、商户、标签、来源通知的摘要。保持传入顺序 */
    suspend fun briefs(txns: List<Txn>): List<TxnBrief> {
        if (txns.isEmpty()) return emptyList()
        val ids = txns.map { it.id }
        val categories = dao.allCategories().associateBy { it.id }
        val allocs = dao.allocations(ids).groupBy { it.txnId }
        val accounts = dao.accounts(txns.mapNotNull { it.accountId }).associateBy { it.id }
        val merchants = dao.merchants(txns.mapNotNull { it.merchantId }).associateBy { it.id }
        val tags = dao.tagsOf(ids).groupBy({ it.txnId }, { it.name })
        val raws = dao.txnRawsOf(ids).groupBy({ it.txnId }, { it.rawId })
        val refunds = dao.linksFrom(ids).filter { it.kind == REFUND }.associate { it.fromId to it.toId }

        fun path(id: Long?): String? {
            val c = categories[id] ?: return null
            val p = c.parentId?.let { categories[it] }
            return if (p == null) c.name else "${p.name}/${c.name}"
        }
        return txns.map { t ->
            val parts = allocs[t.id].orEmpty()
            val category = when {
                parts.size > 1 -> parts.joinToString(" + ") { "${path(it.categoryId) ?: "未归类"} ${Money.yuan(it.amount)}" }
                else -> path(parts.firstOrNull()?.categoryId)
            }
            TxnBrief(
                id = t.id, direction = t.direction, amount = t.amount, occurredAt = t.occurredAt, day = t.day,
                summary = t.summary, note = t.note, category = category,
                account = t.accountId?.let { accounts[it] }?.let(::accountLabel),
                channel = t.channel, merchant = t.merchantId?.let { merchants[it]?.name }, merchantRaw = t.merchantRaw,
                state = t.state, source = t.source, ask = t.ask,
                tags = tags[t.id].orEmpty(), rawIds = raws[t.id].orEmpty(), refundOf = refunds[t.id]
            )
        }
    }

    // ---------------- LedgerBackend：写 ----------------

    override suspend fun record(
        drafts: List<ExpenseDraft>,
        ignored: List<RawVerdict>,
        unreadable: List<RawVerdict>,
        actor: Actor,
        parsedBy: String
    ): List<Long> = db.withTransaction {
        val now = System.currentTimeMillis()
        val createdSubs = HashMap<NewSubcategory, Long>()
        val ids = drafts.map { d ->
            val categoryId = d.categoryId ?: d.newSubcategory?.let { s -> createdSubs.getOrPut(s) { ensureSub(s, actor) } }
            val state = when {
                d.confirmed -> TxnState.CONFIRMED
                d.ask != null -> TxnState.PENDING
                else -> TxnState.AUTO
            }
            val fresh = Txn(
                direction = d.direction, amount = d.amount, occurredAt = d.occurredAt, timeBasis = d.timeBasis,
                day = dayOf(d.occurredAt), accountId = d.account?.let { upsertAccount(it) }, channel = d.channel,
                merchantRaw = d.merchantRaw, merchantId = upsertMerchant(d.merchant, d.merchantRaw, now),
                summary = d.summary, note = d.note,
                source = if (d.rawIds.isEmpty()) TxnSource.CHAT else TxnSource.NOTIFICATION,
                state = state, confidence = d.confidence, ask = if (state == TxnState.PENDING) d.ask else null,
                parsedBy = parsedBy, createdAt = now, updatedAt = now
            )

            val id = if (d.replaces != null) {
                val old = dao.txns(listOf(d.replaces)).single()
                val oldAlloc = dao.allocations(listOf(old.id))
                dao.updateTxn(fresh.copy(id = old.id, createdAt = old.createdAt, note = old.note))
                log(old.id, actor, now, "重新整理",
                    Triple("amount", Money.yuan(old.amount), Money.yuan(fresh.amount)),
                    Triple("direction", old.direction.name, fresh.direction.name),
                    Triple("summary", old.summary, fresh.summary),
                    Triple("state", old.state.name, fresh.state.name),
                    Triple("allocation", oldAlloc.joinToString { "${it.categoryId}:${it.amount}" }, "$categoryId:${d.amount}")
                )
                // 旧的这笔多挂了几条通知、这次没带上的：退回待整理，别让它们悬空
                val dropped = dao.txnRawsOf(listOf(old.id)).map { it.rawId } - d.rawIds.toSet()
                dao.deleteAllocations(old.id)
                dao.deleteTxnRaws(old.id)
                dao.deleteLinksFrom(old.id, REFUND)
                if (dropped.isNotEmpty()) dao.setRawState(dropped, RawState.PENDING, null, now)
                old.id
            } else {
                dao.insertTxn(fresh)
            }
            dao.insertAllocations(listOf(Allocation(txnId = id, categoryId = categoryId, amount = d.amount)))
            if (d.rawIds.isNotEmpty()) {
                dao.insertTxnRaws(d.rawIds.map { TxnRaw(it, id) })
                dao.setRawState(d.rawIds, RawState.DONE, null, now)
            }
            d.refundOf?.let { dao.insertLink(TxnLink(id, it, REFUND)) }
            addTags(id, d.tags, now)
            id
        }
        ignored.forEach { dao.setRawState(listOf(it.rawId), RawState.IGNORED, it.reason, now) }
        unreadable.forEach { dao.setRawState(listOf(it.rawId), RawState.UNREADABLE, it.reason, now) }
        ids
    }

    override suspend fun update(changes: List<TxnChange>, actor: Actor) = db.withTransaction {
        val now = System.currentTimeMillis()
        for (c in changes) {
            val t = dao.txns(listOf(c.txnId)).singleOrNull() ?: continue
            var next = t
            val logs = mutableListOf<Triple<String, String?, String?>>()

            if (c.voidReason != null) {
                // 你说这笔不作数：挂着的通知一并标忽略 —— 退回待整理的话，下一轮整理又会把它记回来
                val raws = dao.txnRawsOf(listOf(t.id)).map { it.rawId }
                dao.deleteTxnRaws(t.id)
                if (raws.isNotEmpty()) dao.setRawState(raws, RawState.IGNORED, "作废：${c.voidReason}", now)
                dao.updateTxn(t.copy(state = TxnState.VOID, ask = null, updatedAt = now))
                log(t.id, actor, now, c.voidReason, Triple("state", t.state.name, "VOID"), Triple("raws", raws.joinToString(), null))
                continue
            }

            val oldAlloc = dao.allocations(listOf(t.id))
            val newAlloc: List<Allocation>? = when {
                c.split != null -> c.split.map { (cat, cents) -> Allocation(txnId = t.id, categoryId = cat, amount = cents) }
                c.categoryId != null || c.newSubcategory != null -> {
                    val cat = c.categoryId ?: ensureSub(c.newSubcategory!!, actor)
                    listOf(Allocation(txnId = t.id, categoryId = cat, amount = t.amount))
                }
                else -> null
            }
            if (newAlloc != null) {
                dao.deleteAllocations(t.id)
                dao.insertAllocations(newAlloc)
                retireEmptyAutoSubs(oldAlloc.mapNotNull { it.categoryId } - newAlloc.mapNotNull { it.categoryId }.toSet())
                // 挂在这笔上的退款，类别跟着走（§10.6 第 11 条）
                val cat = newAlloc.first().categoryId
                dao.linksTo(t.id).filter { it.kind == REFUND }.forEach { link ->
                    val r = dao.txns(listOf(link.fromId)).singleOrNull() ?: return@forEach
                    if (r.state == TxnState.VOID) return@forEach
                    dao.deleteAllocations(r.id)
                    dao.insertAllocations(listOf(Allocation(txnId = r.id, categoryId = cat, amount = r.amount)))
                    dao.updateTxn(r.copy(updatedAt = now))
                    log(r.id, actor, now, "原笔 #${t.id} 改了类别", Triple("allocation", null, "$cat:${r.amount}"))
                }
                logs += Triple(
                    "allocation",
                    oldAlloc.joinToString { "${it.categoryId}:${it.amount}" },
                    newAlloc.joinToString { "${it.categoryId}:${it.amount}" }
                )
            }
            c.summary?.let { logs += Triple("summary", next.summary, it); next = next.copy(summary = it) }
            c.note?.let { logs += Triple("note", next.note, it); next = next.copy(note = it) }
            c.merchant?.let { name ->
                val mid = upsertMerchant(name, next.merchantRaw, now)
                logs += Triple("merchant", next.merchantId?.toString(), mid?.toString())
                next = next.copy(merchantId = mid)
            }
            c.refundOf?.let {
                dao.deleteLinksFrom(t.id, REFUND)
                dao.insertLink(TxnLink(t.id, it, REFUND))
                logs += Triple("refund_of", null, it.toString())
                // 知道退的是哪笔了，就不用再单独问 —— 类别跟原笔走
                if (next.state == TxnState.PENDING) {
                    logs += Triple("state", next.state.name, TxnState.AUTO.name)
                    next = next.copy(state = TxnState.AUTO, ask = null)
                }
            }
            if (c.confirm && next.state != TxnState.CONFIRMED) {
                logs += Triple("state", next.state.name, TxnState.CONFIRMED.name)
                next = next.copy(state = TxnState.CONFIRMED, ask = null)
            }
            if (c.rememberMerchant) {
                val category = (newAlloc ?: oldAlloc).firstOrNull()?.categoryId
                val merchant = next.merchantId?.let { dao.merchants(listOf(it)).firstOrNull() }
                if (merchant != null && category != null) {
                    dao.updateMerchant(merchant.copy(categoryId = category, confirmedAt = now))
                    logs += Triple("merchant_memory", merchant.categoryId?.toString(), category.toString())
                }
            }
            addTags(t.id, c.addTags, now).takeIf { it.isNotEmpty() }?.let { logs += Triple("tags", null, it.joinToString()) }

            dao.updateTxn(next.copy(updatedAt = now))
            log(t.id, actor, now, c.reason, *logs.toTypedArray())
        }
    }

    override suspend fun addCategory(name: String, parentId: Long?, kind: CategoryKind, actor: Actor): Long =
        db.withTransaction {
            val sort = dao.categories().count { it.parentId == parentId }
            dao.insertCategory(Category(name = name, parentId = parentId, kind = kind, sort = sort, createdBy = actor.name))
        }

    // ---------------- 小零件 ----------------

    /**
     * 归类时顺手建的二级（createdBy = AUTO），改账后一笔都不剩了就停用 ——
     * 模型建错一个类别、你在对话里改回来之后，不留空壳。预设的和你亲口要建的不动
     */
    private suspend fun retireEmptyAutoSubs(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val all = dao.allCategories().associateBy { it.id }
        ids.distinct().forEach { id ->
            val c = all[id] ?: return@forEach
            if (c.createdBy == AUTO && c.parentId != null && dao.countAllocations(id) == 0) dao.archiveCategory(id)
        }
    }

    /** 同名二级已经有了就用它（两批里都写了「餐饮/夜宵」） */
    private suspend fun ensureSub(s: NewSubcategory, actor: Actor): Long {
        val all = dao.categories()
        all.firstOrNull { it.parentId == s.parentId && it.name.equals(s.name, ignoreCase = true) }?.let { return it.id }
        val parent = all.first { it.id == s.parentId }
        return dao.insertCategory(
            Category(name = s.name, parentId = s.parentId, kind = parent.kind, sort = all.count { it.parentId == s.parentId }, createdBy = AUTO)
        )
    }

    private suspend fun upsertAccount(a: AccountRef): Long =
        dao.findAccount(a.bank, a.tail, a.type.name)?.id ?: dao.insertAccount(Account(bank = a.bank, tail = a.tail, type = a.type))

    /** 规范名 → 商户；顺手把原样商户串记成别名 */
    private suspend fun upsertMerchant(name: String?, raw: String?, now: Long): Long? {
        val id = name?.takeIf { it.isNotBlank() }?.let { n ->
            dao.merchantByName(n)?.id ?: dao.insertMerchant(Merchant(name = n, createdAt = now))
        } ?: return null
        if (!raw.isNullOrBlank()) dao.insertAlias(MerchantAlias(raw, id))
        return id
    }

    private suspend fun addTags(txnId: Long, names: List<String>, now: Long): List<String> {
        val clean = names.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return clean
        val ids = clean.map { n -> dao.tagByName(n)?.id ?: dao.insertTag(Tag(name = n, createdAt = now)) }
        dao.insertTxnTags(ids.map { TxnTag(txnId, it) })
        return clean
    }

    private suspend fun log(txnId: Long, actor: Actor, at: Long, reason: String?, vararg fields: Triple<String, String?, String?>) {
        val rows = fields.filter { (_, old, new) -> old != new }
            .map { (f, old, new) -> ChangeLog(txnId = txnId, field = f, old = old, new = new, actor = actor, reason = reason, at = at) }
        if (rows.isNotEmpty()) dao.insertChanges(rows)
    }

    fun dayOf(millis: Long): Int {
        val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    /** 整理 agent 要的背景：商户记忆（只有你确认过的） */
    suspend fun merchantMemory(): List<Pair<String, String>> {
        val cats = categories()
        return dao.rememberedMerchants().mapNotNull { m ->
            val path = com.abc.daodian.harness.builtin.ledger.LedgerText.path(m.categoryId, cats) ?: return@mapNotNull null
            m.name to path
        }
    }

    companion object {
        const val REFUND = "REFUND"

        /** category.createdBy：归类时顺手建的二级（区别于预设 PRESET、add_category 建的 USER） */
        const val AUTO = "AUTO"

        fun accountLabel(a: Account): String = listOfNotNull(a.bank, a.tail, a.type.label).joinToString(" ")

        @Volatile private var instance: LedgerStore? = null

        fun get(context: Context): LedgerStore =
            instance ?: synchronized(this) {
                instance ?: LedgerStore(LedgerDatabase.get(context)).also { instance = it }
            }
    }
}
