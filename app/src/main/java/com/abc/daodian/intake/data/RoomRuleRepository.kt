package com.abc.daodian.intake.data

import com.abc.daodian.intake.core.ports.RuleRepository
import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.Rule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 规则存在 `intake.db`。启用着的那几条缓存在内存里：收每一条通知都要读，规则又很少变。
 * 读库和写库排在同一把锁上，写完清缓存，读不到写到一半的
 */
class RoomRuleRepository(db: IntakeDatabase) : RuleRepository {

    private val dao = db.dao()
    private val lock = Mutex()

    @Volatile private var cache: List<Rule>? = null

    override suspend fun enabled(): List<Rule> =
        cache ?: lock.withLock { cache ?: dao.enabledRules().mapNotNull(::ruleOf).also { cache = it } }

    override fun all(): Flow<List<Rule>> = dao.observeRules().map { rows -> rows.mapNotNull(::ruleOf) }

    override suspend fun add(name: String, hard: AllOf, soft: String, at: Long): Rule = write {
        val id = dao.insertRule(RuleRow(name = name, hard = RuleCodec.encode(hard), soft = soft, enabled = true, createdAt = at))
        Rule(id, name, hard, soft, enabled = true, createdAt = at)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean, at: Long) = write {
        dao.setRuleEnabled(id, enabled, stoppedAt = if (enabled) null else at)
    }

    override suspend fun delete(id: Long) = write { dao.deleteRule(id) }

    override suspend fun restore(rule: Rule) = write {
        dao.insertRule(
            RuleRow(rule.id, rule.name, RuleCodec.encode(rule.hard), rule.soft, rule.enabled, rule.createdAt)
        )
        Unit
    }

    private suspend fun <T> write(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } finally {
            cache = null
        }
    }

    /** 硬条件读不懂的（坏了、以后的版本写的）不要：宁可这条不听，不能听宽了 */
    private fun ruleOf(r: RuleRow): Rule? =
        RuleCodec.decode(r.hard)?.let { Rule(r.id, r.name, it, r.soft, r.enabled, r.createdAt) }
}
