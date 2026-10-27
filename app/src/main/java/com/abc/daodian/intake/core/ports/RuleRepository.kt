package com.abc.daodian.intake.core.ports

import com.abc.daodian.intake.core.rule.AllOf
import com.abc.daodian.intake.core.rule.Rule
import kotlinx.coroutines.flow.Flow

/** 规则存哪（Repository）：核心只认这个接口，Room 那份在 `intake/data` */
interface RuleRepository {

    /** 启用着的，按编号。收每一条通知都要读：实现得缓存在内存里，别每条都查库 */
    suspend fun enabled(): List<Rule>

    /** 全部，停了的也在（规则页） */
    fun all(): Flow<List<Rule>>

    /** 建一条，启用着。先过了 `RuleCheck` 才走到这 */
    suspend fun add(name: String, hard: AllOf, soft: String, at: Long): Rule

    /** 停 / 撤销停。改规则 = 停旧的、建新的 */
    suspend fun setEnabled(id: Long, enabled: Boolean, at: Long)

    /** 设置页上删 */
    suspend fun delete(id: Long)
}
