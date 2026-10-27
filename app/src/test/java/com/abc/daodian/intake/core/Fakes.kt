package com.abc.daodian.intake.core

import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.format.Normalized
import com.abc.daodian.intake.core.inbox.Existing
import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.inbox.InboxPolicy
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.intake.core.inbox.Landing
import com.abc.daodian.intake.core.inbox.Stored
import com.abc.daodian.intake.core.inbox.fingerprint
import com.abc.daodian.intake.core.inbox.HandoffState
import com.abc.daodian.intake.core.ports.Due
import com.abc.daodian.intake.core.ports.HandoffRepository
import com.abc.daodian.intake.core.ports.InboxRepository
import com.abc.daodian.intake.core.ports.RuleRepository
import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.rule.Rule
import com.abc.daodian.intake.core.rule.RuleDraft
import com.abc.daodian.intake.core.steward.Decision
import com.abc.daodian.intake.core.steward.Settlement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

const val SMS = "com.android.mms"
const val CAINIAO = "com.cainiao.wireless"
const val WECHAT = "com.tencent.mm"
const val CMB = "cmb.pb"

val NAMES = AppNames { mapOf(SMS to "短信", CAINIAO to "菜鸟", WECHAT to "微信", CMB to "招商银行")[it] ?: it }

fun notice(
    pkg: String = SMS,
    title: String? = null,
    text: String? = null,
    key: String = "k1",
    postTime: Long = 1_000,
    extra: String? = null,
    redacted: Boolean = false
) = Notice(pkg, key, postTime, postTime, title, text, extra, "{}", redacted, "posted")

fun rule(id: Long, draft: RuleDraft, enabled: Boolean = true) = Rule(id, draft.name, draft.hard(), draft.soft, enabled, 0)

class FakeRules(var list: List<Rule> = emptyList()) : RuleRepository {
    override suspend fun enabled() = list.filter { it.enabled }.sortedBy { it.id }
    override fun all(): Flow<List<Rule>> = MutableStateFlow(list)
    override suspend fun add(name: String, hard: AllOf, soft: String, at: Long): Rule =
        Rule((list.maxOfOrNull { it.id } ?: 0) + 1, name, hard, soft, true, at).also { list = list + it }
    override suspend fun setEnabled(id: Long, enabled: Boolean, at: Long) {
        list = list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }
    override suspend fun delete(id: Long) {
        list = list.filterNot { it.id == id }
    }
}

/** 内存里的收件箱：找 Existing、照 Landing 落，和 Room 那份该做的一样。管家那几样这里用不着 */
class FakeInbox : InboxRepository {

    data class Row(
        val id: Long, val pkg: String, val key: String, val postTime: Long, val fingerprint: String,
        val text: String?, val redacted: Boolean, val state: InboxState, val rules: Set<Long>, val attempts: Int,
        val title: String? = null, val body: String? = null, val receivedAt: Long = 0
    )

    /** (通知, 规则) → 「交给 ledger,reminder」/「不相关：广告」 */
    val decided = linkedMapOf<Pair<Long, Long>, String>()
    val handoffs = mutableListOf<Decision.HandOff>()

    val rows = linkedMapOf<Long, Row>()
    var lookups = 0
    private var nextId = 1L

    override suspend fun receive(n: Normalized, matched: Set<Long>, lastLook: Boolean, at: Long): Stored? {
        lookups++
        val raw = n.raw
        val same = rows.values.firstOrNull { it.fingerprint == raw.fingerprint }
        val found = same ?: rows.values.firstOrNull {
            it.state == InboxState.WAITING && it.pkg == raw.pkg && it.key == raw.key && it.postTime == raw.postTime
        }
        val existing = found?.let { Existing(it.id, it.state, it.rules, sameText = it === same) }
        return when (val l = Landing.of(raw.redacted, lastLook, matched, existing)) {
            is Landing.Insert -> {
                val id = nextId++
                rows[id] = Row(id, raw.pkg, raw.key, raw.postTime, raw.fingerprint, raw.text, raw.redacted, l.state, l.rules, 0, n.title, n.body, at)
                Stored(id, l.state, fresh = true)
            }
            is Landing.AddRules -> {
                val r = rows.getValue(l.id)
                val restart = r.state in setOf(InboxState.DONE, InboxState.FAILED) && l.state == InboxState.PENDING
                rows[l.id] = r.copy(rules = r.rules + l.rules, state = r.state.to(l.state), attempts = if (restart) 0 else r.attempts)
                Stored(l.id, l.state, fresh = false)
            }
            is Landing.Replace -> {
                val r = rows.getValue(l.id)
                rows[l.id] = r.copy(
                    fingerprint = raw.fingerprint, text = raw.text, redacted = false, rules = l.rules, state = r.state.to(InboxState.PENDING),
                    title = n.title, body = n.body
                )
                Stored(l.id, InboxState.PENDING, fresh = false)
            }
            is Landing.Delete -> {
                rows.remove(l.id)
                null
            }
            Landing.Skip -> null
        }
    }

    fun set(id: Long, state: InboxState, attempts: Int = 0) {
        rows[id] = rows.getValue(id).copy(state = state, attempts = attempts)
    }

    override fun pendingCount(): Flow<Int> = MutableStateFlow(rows.values.count { it.state == InboxState.PENDING })
    override suspend fun claim(limit: Int, now: Long): List<InboxNotice> {
        rows.replaceAll { _, r ->
            when {
                r.state == InboxState.WORKING -> r.copy(state = InboxState.PENDING)
                r.state == InboxState.WAITING && r.receivedAt <= now - InboxPolicy.WAIT_MAX_MILLIS -> r.copy(state = InboxState.PENDING)
                else -> r
            }
        }
        val picked = rows.values.filter { it.state == InboxState.PENDING }.sortedWith(compareBy({ it.receivedAt }, { it.id })).take(limit)
        picked.forEach { rows[it.id] = it.copy(state = it.state.to(InboxState.WORKING)) }
        return picked.map { r ->
            InboxNotice(r.id, r.pkg, r.postTime, r.title, r.body, null, r.redacted, r.rules.filter { (r.id to it) !in decided }.toSet(), r.attempts)
        }
    }

    override suspend fun settle(s: Settlement, at: Long) {
        for (d in s.decisions) when (d) {
            is Decision.HandOff -> {
                handoffs += d
                d.rules.forEach { r -> decided[d.noticeId to r] = (decided[d.noticeId to r]?.let { "$it," } ?: "交给 ") + d.to }
            }
            is Decision.Ignore -> d.rules.forEach { decided[d.noticeId to it] = "不相关：${d.why}" }
        }
        for (n in s.notices) {
            val r = rows.getValue(n.id)
            if (r.state != InboxState.WORKING) continue
            val open = r.rules.any { (r.id to it) !in decided }
            val state = if (n.state == InboxState.DONE && open) InboxState.PENDING else n.state
            rows[n.id] = r.copy(state = r.state.to(state), attempts = n.attempts)
        }
    }

    override suspend fun purgeDone(before: Long) = throw UnsupportedOperationException()
}

/** 内存里的交接表，和 RoomHandoffRepository 一样的写法 */
class FakeHandoffs : HandoffRepository {

    data class Row(val due: Due, val state: HandoffState = HandoffState.DUE, val receipt: String? = null, val error: String? = null)

    val rows = linkedMapOf<Long, Row>()

    fun add(id: Long, target: String, notice: Notice = notice(), note: String = "") {
        rows[id] = Row(Due(id, id, target, note, 0, notice, notice.title, notice.text))
    }

    override suspend fun due(limit: Int): List<Due> = rows.values.filter { it.state == HandoffState.DUE }.map { it.due }.take(limit)

    override suspend fun delivered(id: Long, receipt: String, route: String?, at: Long) {
        rows[id] = rows.getValue(id).copy(state = HandoffState.DELIVERED, receipt = receipt, error = null)
    }

    override suspend fun missed(id: Long, why: String, at: Long): HandoffState {
        val r = rows.getValue(id)
        val tries = r.due.attempts + 1
        val state = if (tries >= InboxPolicy.MAX_ATTEMPTS) HandoffState.FAILED else HandoffState.DUE
        rows[id] = r.copy(due = r.due.copy(attempts = tries), state = state, error = why)
        return state
    }
}
