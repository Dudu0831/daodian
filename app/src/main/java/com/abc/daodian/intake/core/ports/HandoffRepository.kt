package com.abc.daodian.intake.core.ports

import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.inbox.HandoffState

/**
 * 待交的一次交接，连着收件箱里那一条（原样的、整理过的都在）
 *
 * @param target 交给谁（模块的 `NoticeTarget.id`）
 * @param attempts 交过几次都没交成
 */
data class Due(
    val id: Long,
    val noticeId: Long,
    val target: String,
    val note: String,
    val attempts: Int,
    val notice: Notice,
    val title: String?,
    val body: String?
)

/** 交接（Outbox）存哪：管家落库时写下待交的，`Dispatcher` 从这里取、交、记结果 */
interface HandoffRepository {

    /** 待交的，最早的在前 */
    suspend fun due(limit: Int): List<Due>

    /** 交成了，带着模块的回执 */
    suspend fun delivered(id: Long, receipt: String, route: String?, at: Long)

    /** 这次没交成：次数 +1，第 [com.abc.daodian.intake.core.inbox.InboxPolicy.MAX_ATTEMPTS] 次标交不出。返回交完的状态 */
    suspend fun missed(id: Long, why: String, at: Long): HandoffState
}
