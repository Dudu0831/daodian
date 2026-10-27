package com.abc.daodian.intake.core.ports

import com.abc.daodian.intake.core.format.Normalized
import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.inbox.InboxPolicy
import com.abc.daodian.intake.core.inbox.Landing
import com.abc.daodian.intake.core.inbox.Stored
import com.abc.daodian.intake.core.steward.Settlement
import kotlinx.coroutines.flow.Flow

/** 收件箱存哪（Repository）：核心只认这个接口，Room 那份在 `intake/data` */
interface InboxRepository {

    /**
     * 一条到了门口：找出 [com.abc.daodian.intake.core.inbox.Existing]（同指纹的；没有就找同一条通知还在等的遮蔽版），
     * 照 [Landing.of] 落库，一个事务。返回落下的那一行；[Landing.Skip]、[Landing.Delete] 是 null
     */
    suspend fun receive(n: Normalized, matched: Set<Long>, lastLook: Boolean, at: Long): Stored?

    /** 待办几条（界面、诊断看）。管家不靠它醒，靠 `Wake` 叫 */
    fun pendingCount(): Flow<Int>

    /**
     * 领一批待办转成办着，最早的在前，最多 [limit] 条。先把两种卡住的放回待办：等了超过 [InboxPolicy.WAIT_MAX_MILLIS] 的遮蔽版、
     * 还是办着的（一次只跑一轮，领的时候还办着的，是上回领走后进程没了）
     */
    suspend fun claim(limit: Int, now: Long): List<InboxNotice>

    /**
     * 一轮落库，一个事务：写决定、写交接（`handoffs`，Outbox）、改每条的状态和次数。
     * 领走之后又中了新规则的（还有没说法的规则），算出来是办完也转回待办、次数不加
     */
    suspend fun settle(s: Settlement, at: Long)

    /** 清 [before] 之前办完的（[InboxPolicy.KEEP_DAYS]）。待办、没办成的不清 */
    suspend fun purgeDone(before: Long)
}
