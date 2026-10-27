package com.abc.daodian.intake.data

import android.content.Context
import com.abc.daodian.intake.core.ports.HandoffRepository
import com.abc.daodian.intake.core.ports.InboxRepository
import com.abc.daodian.intake.core.ports.RuleRepository

/**
 * `intake.db` 的几个仓库，全 app 各一份。规则仓库把启用着的缓存在内存里：收通知的流水线和助手建规则的工具
 * 必须用同一份，不然这边建了、那边还拿着旧缓存
 */
object IntakeRepos {

    @Volatile private var rules: RuleRepository? = null
    @Volatile private var inbox: InboxRepository? = null
    @Volatile private var handoffs: HandoffRepository? = null

    fun rules(context: Context): RuleRepository =
        rules ?: synchronized(this) { rules ?: RoomRuleRepository(IntakeDatabase.get(context)).also { rules = it } }

    fun inbox(context: Context): InboxRepository =
        inbox ?: synchronized(this) { inbox ?: RoomInboxRepository(IntakeDatabase.get(context)).also { inbox = it } }

    fun handoffs(context: Context): HandoffRepository =
        handoffs ?: synchronized(this) { handoffs ?: RoomHandoffRepository(IntakeDatabase.get(context)).also { handoffs = it } }
}
