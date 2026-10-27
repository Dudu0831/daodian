package com.abc.daodian.intake.core

import com.abc.daodian.intake.Notice
import com.abc.daodian.intake.core.format.Normalized
import com.abc.daodian.intake.core.inbox.Existing
import com.abc.daodian.intake.core.inbox.InboxNotice
import com.abc.daodian.intake.core.inbox.InboxState
import com.abc.daodian.intake.core.inbox.Landing
import com.abc.daodian.intake.core.inbox.Stored
import com.abc.daodian.intake.core.inbox.fingerprint
import com.abc.daodian.intake.core.ports.InboxRepository
import com.abc.daodian.intake.core.ports.RuleRepository
import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.AppNames
import com.abc.daodian.intake.core.rule.Rule
import com.abc.daodian.intake.core.rule.RuleDraft
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
        val text: String?, val redacted: Boolean, val state: InboxState, val rules: Set<Long>, val attempts: Int
    )

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
                rows[id] = Row(id, raw.pkg, raw.key, raw.postTime, raw.fingerprint, raw.text, raw.redacted, l.state, l.rules, 0)
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
                rows[l.id] = r.copy(fingerprint = raw.fingerprint, text = raw.text, redacted = false, rules = l.rules, state = r.state.to(InboxState.PENDING))
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
    override suspend fun claim(limit: Int, now: Long): List<InboxNotice> = throw UnsupportedOperationException()
    override suspend fun settle(s: Settlement, at: Long) = throw UnsupportedOperationException()
    override suspend fun purgeDone(before: Long) = throw UnsupportedOperationException()
}
