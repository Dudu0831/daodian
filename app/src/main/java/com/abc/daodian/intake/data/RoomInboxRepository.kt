package com.abc.daodian.intake.data

import androidx.room.withTransaction
import com.abc.daodian.intake.core.format.Normalized
import com.abc.daodian.intake.core.inbox.Existing
import com.abc.daodian.intake.core.inbox.HandoffState
import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.inbox.InboxPolicy
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.intake.core.inbox.Landing
import com.abc.daodian.intake.core.inbox.Stored
import com.abc.daodian.intake.core.inbox.fingerprint
import com.abc.daodian.intake.core.ports.InboxRepository
import com.abc.daodian.intake.core.steward.Decision
import com.abc.daodian.intake.core.steward.Settlement
import kotlinx.coroutines.flow.Flow

/** 收件箱存在 `intake.db`。怎么落、怎么算都在核心里（[Landing]、[Settlement]），这里只照着写库，每样一个事务 */
class RoomInboxRepository(private val db: IntakeDatabase) : InboxRepository {

    private val dao = db.dao()

    override suspend fun receive(n: Normalized, matched: Set<Long>, lastLook: Boolean, at: Long): Stored? = db.withTransaction {
        val raw = n.raw
        val same = dao.byFingerprint(raw.fingerprint)
        val row = same ?: dao.waitingTwin(raw.pkg, raw.key, raw.postTime)
        val existing = row?.let { Existing(it.id, it.state, dao.ruleIdsOf(it.id).toSet(), sameText = same != null) }
        when (val l = Landing.of(raw.redacted, lastLook, matched, existing)) {
            is Landing.Insert -> {
                val id = dao.insertNotice(rowOf(n, l.state, receivedAt = at))
                dao.insertNoticeRules(l.rules.map { NoticeRuleRow(id, it) })
                Stored(id, l.state, fresh = true)
            }
            is Landing.AddRules -> {
                val old = checkNotNull(row)
                val state = old.state.to(l.state)
                val restart = old.state != state && (old.state == InboxState.DONE || old.state == InboxState.FAILED)
                dao.updateNotice(
                    old.copy(
                        state = state,
                        attempts = if (restart) 0 else old.attempts,
                        doneAt = if (state == InboxState.DONE) old.doneAt else null
                    )
                )
                dao.insertNoticeRules(l.rules.map { NoticeRuleRow(old.id, it) })
                Stored(old.id, state, fresh = false)
            }
            is Landing.Replace -> {
                val old = checkNotNull(row)
                val state = old.state.to(InboxState.PENDING)
                dao.updateNotice(rowOf(n, state, receivedAt = old.receivedAt).copy(id = old.id))
                dao.clearNoticeRules(old.id)
                dao.insertNoticeRules(l.rules.map { NoticeRuleRow(old.id, it) })
                Stored(old.id, state, fresh = false)
            }
            is Landing.Delete -> {
                dao.clearNoticeRules(l.id)
                dao.deleteNotice(l.id)
                null
            }
            Landing.Skip -> null
        }
    }

    override fun pendingCount(): Flow<Int> = dao.observePending()

    override suspend fun claim(limit: Int, now: Long): List<InboxNotice> = db.withTransaction {
        dao.releaseWorking()
        dao.releaseWaiting(before = now - InboxPolicy.WAIT_MAX_MILLIS)
        val rows = dao.pending(limit)
        if (rows.isEmpty()) return@withTransaction emptyList()
        val ids = rows.map { it.id }
        dao.markWorking(ids)
        val open = dao.undecided(ids).groupBy({ it.noticeId }, { it.ruleId })
        rows.map {
            InboxNotice(it.id, it.pkg, it.at, it.normTitle, it.normBody, it.extra, it.redacted, open[it.id].orEmpty().toSet(), it.attempts)
        }
    }

    override suspend fun settle(s: Settlement, at: Long) = db.withTransaction {
        // 一条规则可能交给几个去处（信用卡账单：记账 + 提醒还款），先并起来再写
        val targets = LinkedHashMap<Pair<Long, Long>, MutableList<String>>()
        for (d in s.decisions) when (d) {
            is Decision.HandOff -> {
                dao.insertHandoff(HandoffRow(noticeId = d.noticeId, target = d.to, note = d.note, state = HandoffState.DUE, createdAt = at))
                d.rules.forEach { targets.getOrPut(d.noticeId to it) { mutableListOf() } += d.to }
            }
            is Decision.Ignore -> d.rules.forEach { dao.decide(d.noticeId, it, RuleOutcome.IGNORED, targets = null, why = d.why, at = at) }
        }
        targets.forEach { (key, to) -> dao.decide(key.first, key.second, RuleOutcome.HANDED, to.joinToString(","), why = null, at = at) }
        for (n in s.notices) {
            // 领走之后又中了新规则的：这一轮没给管家看，还得再办一次，不算次数
            val state = if (n.state == InboxState.DONE && dao.countUndecided(n.id) > 0) InboxState.PENDING else n.state
            dao.settleNotice(n.id, state, n.attempts, doneAt = if (state == InboxState.DONE) at else null)
        }
    }

    override suspend fun purgeDone(before: Long) = db.withTransaction {
        dao.purgeDoneRules(before)
        dao.purgeDoneHandoffs(before)
        dao.purgeDoneNotices(before)
    }

    private fun rowOf(n: Normalized, state: InboxState, receivedAt: Long): NoticeRow {
        val raw = n.raw
        return NoticeRow(
            pkg = raw.pkg, notifKey = raw.key, postTime = raw.postTime, at = raw.at,
            title = raw.title, text = raw.text, extra = raw.extra, extras = raw.extras, redacted = raw.redacted, how = raw.how,
            fingerprint = raw.fingerprint, normTitle = n.title, normBody = n.body,
            state = state, receivedAt = receivedAt
        )
    }
}
