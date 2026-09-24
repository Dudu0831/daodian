package com.abc.daodian.ledger.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** 统计查询的一行：某个键（一级类别 / 日期 / 方向）→ 金额（分）和笔数 */
data class Sum(val key: Long?, val amount: Long, val n: Int)

data class DirectionSum(val direction: String, val amount: Long, val n: Int)

@Dao
interface LedgerDao {

    // ---------------- 原始通知 ----------------

    /** 指纹重复（同一条通知同一段正文）就不插，返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRaw(raw: RawNotification): Long

    @Query("SELECT * FROM raw_notification WHERE id IN (:ids)")
    suspend fun raws(ids: Collection<Long>): List<RawNotification>

    /** 待整理的，冷却期（[before]）之后到的先不给 */
    @Query("SELECT * FROM raw_notification WHERE state = 'PENDING' AND postTime <= :before ORDER BY postTime LIMIT :limit")
    suspend fun pendingRaws(before: Long, limit: Int): List<RawNotification>

    @Query("SELECT COUNT(*) FROM raw_notification WHERE state = 'PENDING' AND postTime <= :before")
    suspend fun countPendingRaws(before: Long): Int

    @Query("SELECT COUNT(*) FROM raw_notification WHERE state = 'PENDING'")
    fun observePendingRaws(): Flow<Int>

    @Query("SELECT * FROM raw_notification WHERE state = 'UNREADABLE' ORDER BY postTime")
    suspend fun unreadableRaws(): List<RawNotification>

    @Query("SELECT COUNT(*) FROM raw_notification WHERE notifKey = :key AND postTime = :postTime AND redacted = 0")
    suspend fun countReal(key: String, postTime: Long): Int

    /** 同一条通知的真正文到了：之前收到的遮蔽版作废，不用模型判断 */
    @Query(
        "UPDATE raw_notification SET state = 'SUPERSEDED', processedAt = :at " +
            "WHERE notifKey = :key AND postTime = :postTime AND redacted = 1 AND state IN ('PENDING', 'UNREADABLE')"
    )
    suspend fun supersedeRedacted(key: String, postTime: Long, at: Long)

    @Query("UPDATE raw_notification SET state = :state, stateNote = :note, processedAt = :at WHERE id IN (:ids)")
    suspend fun setRawState(ids: Collection<Long>, state: RawState, note: String?, at: Long)

    // 抓取页（ledger/presentation/CaptureScreen）看的

    @Query("SELECT COUNT(*) FROM raw_notification")
    fun observeRawCount(): Flow<Int>

    @Query("SELECT * FROM raw_notification ORDER BY postTime DESC LIMIT :limit")
    fun observeRecentRaws(limit: Int): Flow<List<RawNotification>>

    /** 手动抓一下之后数新存了几条 */
    @Query("SELECT COUNT(*) FROM raw_notification WHERE capturedAt >= :since")
    suspend fun countCapturedSince(since: Long): Int

    /** 最近一次实时回调收到的时刻：比这更晚的支付没进来，就是实时回调断了 */
    @Query("SELECT MAX(capturedAt) FROM raw_notification WHERE capturedHow = 'posted'")
    fun observeLastPosted(): Flow<Long?>

    // ---------------- 流水 ----------------

    @Insert
    suspend fun insertTxn(txn: Txn): Long

    @Update
    suspend fun updateTxn(txn: Txn)

    @Query("SELECT * FROM txn WHERE id IN (:ids)")
    suspend fun txns(ids: Collection<Long>): List<Txn>

    @RawQuery
    suspend fun queryTxns(query: SupportSQLiteQuery): List<Txn>

    @Query("SELECT * FROM allocation WHERE txnId IN (:txnIds)")
    suspend fun allocations(txnIds: Collection<Long>): List<Allocation>

    @Insert
    suspend fun insertAllocations(list: List<Allocation>)

    @Query("DELETE FROM allocation WHERE txnId = :txnId")
    suspend fun deleteAllocations(txnId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTxnRaws(list: List<TxnRaw>)

    @Query("DELETE FROM txn_raw WHERE txnId = :txnId")
    suspend fun deleteTxnRaws(txnId: Long)

    @Query("SELECT * FROM txn_raw WHERE txnId IN (:txnIds)")
    suspend fun txnRawsOf(txnIds: Collection<Long>): List<TxnRaw>

    @Query("SELECT * FROM txn_raw WHERE rawId IN (:rawIds)")
    suspend fun ownersOf(rawIds: Collection<Long>): List<TxnRaw>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: TxnLink)

    @Query("DELETE FROM txn_link WHERE fromId = :fromId AND kind = :kind")
    suspend fun deleteLinksFrom(fromId: Long, kind: String)

    @Query("SELECT * FROM txn_link WHERE fromId IN (:ids)")
    suspend fun linksFrom(ids: Collection<Long>): List<TxnLink>

    @Query("SELECT * FROM txn_link WHERE toId = :id")
    suspend fun linksTo(id: Long): List<TxnLink>

    // ---------------- 类别 / 商户 / 账户 / 标签 ----------------

    @Query("SELECT * FROM category WHERE archived = 0 ORDER BY kind, parentId IS NOT NULL, sort, id")
    suspend fun categories(): List<Category>

    @Query("SELECT * FROM category ORDER BY kind, parentId IS NOT NULL, sort, id")
    fun observeCategories(): Flow<List<Category>>

    /** 连停用的也要：旧账可能还挂在停用的类别上 */
    @Query("SELECT * FROM category")
    suspend fun allCategories(): List<Category>

    @Insert
    suspend fun insertCategory(c: Category): Long

    @Query("SELECT COUNT(*) FROM allocation WHERE categoryId = :categoryId")
    suspend fun countAllocations(categoryId: Long): Int

    @Query("UPDATE category SET archived = 1 WHERE id = :id")
    suspend fun archiveCategory(id: Long)

    @Query("SELECT * FROM merchant WHERE name = :name")
    suspend fun merchantByName(name: String): Merchant?

    @Query("SELECT * FROM merchant WHERE id IN (:ids)")
    suspend fun merchants(ids: Collection<Long>): List<Merchant>

    @Query("SELECT * FROM merchant WHERE categoryId IS NOT NULL ORDER BY name")
    suspend fun rememberedMerchants(): List<Merchant>

    @Insert
    suspend fun insertMerchant(m: Merchant): Long

    @Update
    suspend fun updateMerchant(m: Merchant)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(a: MerchantAlias)

    @Query("SELECT * FROM merchant_alias WHERE alias IN (:aliases)")
    suspend fun aliases(aliases: Collection<String>): List<MerchantAlias>

    @Query("SELECT * FROM account WHERE bank = :bank AND type = :type AND ((tail IS NULL AND :tail IS NULL) OR tail = :tail)")
    suspend fun findAccount(bank: String, tail: String?, type: String): Account?

    @Query("SELECT * FROM account WHERE id IN (:ids)")
    suspend fun accounts(ids: Collection<Long>): List<Account>

    @Insert
    suspend fun insertAccount(a: Account): Long

    @Query("SELECT * FROM tag WHERE name = :name")
    suspend fun tagByName(name: String): Tag?

    @Insert
    suspend fun insertTag(t: Tag): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTxnTags(list: List<TxnTag>)

    /** 流水 id → 标签名 */
    @Query("SELECT txn_tag.txnId AS txnId, tag.name AS name FROM txn_tag JOIN tag ON tag.id = txn_tag.tagId WHERE txn_tag.txnId IN (:txnIds)")
    suspend fun tagsOf(txnIds: Collection<Long>): List<TxnTagName>

    // ---------------- 改动历史 / 运行记录 ----------------

    @Insert
    suspend fun insertChanges(list: List<ChangeLog>)

    @Query("SELECT * FROM change_log WHERE txnId = :txnId ORDER BY at")
    suspend fun changesOf(txnId: Long): List<ChangeLog>

    @Insert
    suspend fun insertRun(run: AgentRun): Long

    @Update
    suspend fun updateRun(run: AgentRun)

    @Query("SELECT * FROM agent_run ORDER BY startedAt DESC LIMIT 1")
    fun observeLastRun(): Flow<AgentRun?>

    // ---------------- 统计（口径见 LEDGER_PLAN.md §10.4）----------------

    /** 一段日子里各方向的合计 */
    @Query(
        "SELECT direction, SUM(amount) AS amount, COUNT(*) AS n FROM txn " +
            "WHERE state != 'VOID' AND day BETWEEN :from AND :to GROUP BY direction"
    )
    fun observeTotals(from: Int, to: Int): Flow<List<DirectionSum>>

    /**
     * 按一级类别分。[income] = false 时是支出口径（OUT 加、REFUND 减），true 时是收入。
     * 未归类的 key 是 null
     */
    @Query(
        "SELECT CASE WHEN c.id IS NULL THEN NULL ELSE COALESCE(c.parentId, c.id) END AS `key`, " +
            "SUM(CASE t.direction WHEN 'REFUND' THEN -a.amount ELSE a.amount END) AS amount, " +
            "COUNT(DISTINCT t.id) AS n " +
            "FROM allocation a JOIN txn t ON t.id = a.txnId LEFT JOIN category c ON c.id = a.categoryId " +
            "WHERE t.state != 'VOID' AND t.day BETWEEN :from AND :to " +
            "AND ((:income = 0 AND t.direction IN ('OUT', 'REFUND')) OR (:income = 1 AND t.direction = 'IN')) " +
            "GROUP BY `key`"
    )
    fun observeByTop(from: Int, to: Int, income: Boolean): Flow<List<Sum>>

    /** 某个一级类别里按叶子分（一级自己没二级时就是它自己） */
    @Query(
        "SELECT a.categoryId AS `key`, " +
            "SUM(CASE t.direction WHEN 'REFUND' THEN -a.amount ELSE a.amount END) AS amount, " +
            "COUNT(DISTINCT t.id) AS n " +
            "FROM allocation a JOIN txn t ON t.id = a.txnId JOIN category c ON c.id = a.categoryId " +
            "WHERE t.state != 'VOID' AND t.day BETWEEN :from AND :to AND (c.id = :top OR c.parentId = :top) " +
            "AND t.direction IN ('OUT', 'REFUND', 'IN') " +
            "GROUP BY a.categoryId"
    )
    fun observeLeaves(from: Int, to: Int, top: Long): Flow<List<Sum>>

    /** 每天的净支出（OUT − REFUND），画柱子用 */
    @Query(
        "SELECT day AS `key`, SUM(CASE direction WHEN 'OUT' THEN amount ELSE -amount END) AS amount, COUNT(*) AS n " +
            "FROM txn WHERE state != 'VOID' AND day BETWEEN :from AND :to AND direction IN ('OUT', 'REFUND') GROUP BY day"
    )
    fun observeDaily(from: Int, to: Int): Flow<List<Sum>>

    @Query("SELECT COUNT(*) FROM txn WHERE state = 'PENDING'")
    fun observePendingTxns(): Flow<Int>

    @Query("SELECT MIN(day) FROM txn WHERE state != 'VOID'")
    fun observeFirstDay(): Flow<Int?>

    /** 有流水变动时发一声，界面据此重查明细 */
    @Query("SELECT MAX(updatedAt) FROM txn")
    fun observeLastChange(): Flow<Long?>
}

data class TxnTagName(val txnId: Long, val name: String)
