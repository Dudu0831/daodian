package com.abc.daodian.intake.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.abc.daodian.intake.core.inbox.InboxState
import kotlinx.coroutines.flow.Flow

/**
 * 状态只按 [com.abc.daodian.intake.core.inbox.InboxState] 的转移表改：成批改的那几条在 WHERE 里写明从哪个状态来，
 * 一行一行改的由仓库先过 `InboxState.to`
 */
@Dao
interface IntakeDao {

    // ---------------- 规则 ----------------

    @Insert
    suspend fun insertRule(r: RuleRow): Long

    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY id")
    suspend fun enabledRules(): List<RuleRow>

    @Query("SELECT * FROM rules ORDER BY id")
    fun observeRules(): Flow<List<RuleRow>>

    @Query("UPDATE rules SET enabled = :enabled, stoppedAt = :stoppedAt WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean, stoppedAt: Long?)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    // ---------------- 收件箱：收 ----------------

    @Query("SELECT * FROM notices WHERE fingerprint = :fingerprint")
    suspend fun byFingerprint(fingerprint: String): NoticeRow?

    /** 同一条通知还在等真正文的遮蔽版 */
    @Query("SELECT * FROM notices WHERE pkg = :pkg AND notifKey = :key AND postTime = :postTime AND state = 'WAITING' LIMIT 1")
    suspend fun waitingTwin(pkg: String, key: String, postTime: Long): NoticeRow?

    @Query("SELECT ruleId FROM notice_rules WHERE noticeId = :id")
    suspend fun ruleIdsOf(id: Long): List<Long>

    @Insert
    suspend fun insertNotice(n: NoticeRow): Long

    @Update
    suspend fun updateNotice(n: NoticeRow)

    @Query("DELETE FROM notices WHERE id = :id")
    suspend fun deleteNotice(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNoticeRules(rows: List<NoticeRuleRow>)

    @Query("DELETE FROM notice_rules WHERE noticeId = :id")
    suspend fun clearNoticeRules(id: Long)

    // ---------------- 收件箱：管家 ----------------

    @Query("SELECT COUNT(*) FROM notices WHERE state = 'PENDING'")
    fun observePending(): Flow<Int>

    /** 还是办着的：一次只跑一轮，领的时候还办着，是上回领走后进程没了 */
    @Query("UPDATE notices SET state = 'PENDING' WHERE state = 'WORKING'")
    suspend fun releaseWorking()

    @Query("UPDATE notices SET state = 'PENDING' WHERE state = 'WAITING' AND receivedAt <= :before")
    suspend fun releaseWaiting(before: Long)

    @Query("SELECT * FROM notices WHERE state = 'PENDING' ORDER BY receivedAt, id LIMIT :limit")
    suspend fun pending(limit: Int): List<NoticeRow>

    @Query("UPDATE notices SET state = 'WORKING' WHERE state = 'PENDING' AND id IN (:ids)")
    suspend fun markWorking(ids: List<Long>)

    @Query("SELECT * FROM notice_rules WHERE noticeId IN (:ids) AND outcome IS NULL")
    suspend fun undecided(ids: List<Long>): List<NoticeRuleRow>

    @Query("SELECT COUNT(*) FROM notice_rules WHERE noticeId = :id AND outcome IS NULL")
    suspend fun countUndecided(id: Long): Int

    @Query(
        "UPDATE notice_rules SET outcome = :outcome, targets = :targets, why = :why, decidedAt = :at " +
            "WHERE noticeId = :noticeId AND ruleId = :ruleId"
    )
    suspend fun decide(noticeId: Long, ruleId: Long, outcome: RuleOutcome, targets: String?, why: String?, at: Long)

    /** 一轮落库时改状态：只改还办着的 */
    @Query("UPDATE notices SET state = :state, attempts = :attempts, doneAt = :doneAt WHERE id = :id AND state = 'WORKING'")
    suspend fun settleNotice(id: Long, state: InboxState, attempts: Int, doneAt: Long?)

    @Insert
    suspend fun insertHandoff(h: HandoffRow): Long

    // ---------------- 清 ----------------

    @Query("DELETE FROM notice_rules WHERE noticeId IN (SELECT id FROM notices WHERE state = 'DONE' AND doneAt < :before)")
    suspend fun purgeDoneRules(before: Long)

    @Query("DELETE FROM handoffs WHERE noticeId IN (SELECT id FROM notices WHERE state = 'DONE' AND doneAt < :before)")
    suspend fun purgeDoneHandoffs(before: Long)

    @Query("DELETE FROM notices WHERE state = 'DONE' AND doneAt < :before")
    suspend fun purgeDoneNotices(before: Long)
}
