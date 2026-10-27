package com.abc.daodian.intake.data

import androidx.room.withTransaction
import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.inbox.HandoffState
import com.abc.daodian.intake.core.inbox.InboxPolicy
import com.abc.daodian.intake.core.ports.Due
import com.abc.daodian.intake.core.ports.HandoffRepository

/** 交接存在 `intake.db` 的 `handoffs`。交接连着的收件箱那一条一起读出来 */
class RoomHandoffRepository(private val db: IntakeDatabase) : HandoffRepository {

    private val dao = db.dao()

    override suspend fun due(limit: Int): List<Due> = db.withTransaction {
        val rows = dao.dueHandoffs(limit)
        if (rows.isEmpty()) return@withTransaction emptyList()
        val notices = dao.notices(rows.map { it.noticeId }.distinct()).associateBy { it.id }
        rows.mapNotNull { h ->
            val n = notices[h.noticeId] ?: return@mapNotNull null
            Due(
                h.id, h.noticeId, h.target, h.note, h.attempts,
                Notice(n.pkg, n.notifKey, n.postTime, n.at, n.title, n.text, n.extra, n.extras, n.redacted, n.how),
                n.normTitle, n.normBody
            )
        }
    }

    override suspend fun delivered(id: Long, receipt: String, route: String?, at: Long) = dao.delivered(id, receipt, route, at)

    override suspend fun missed(id: Long, why: String, at: Long): HandoffState = db.withTransaction {
        val h = dao.handoff(id) ?: return@withTransaction HandoffState.FAILED
        val tries = h.attempts + 1
        val state = if (tries >= InboxPolicy.MAX_ATTEMPTS) HandoffState.FAILED else HandoffState.DUE
        dao.missed(id, state, tries, why)
        state
    }
}
