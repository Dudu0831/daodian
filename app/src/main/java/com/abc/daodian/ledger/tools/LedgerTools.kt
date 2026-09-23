package com.abc.daodian.ledger.tools

import com.abc.daodian.agent.engine.background.LockedTool
import com.abc.daodian.agent.engine.tool.Tool
import com.abc.daodian.ledger.domain.LedgerBackend
import java.time.ZoneId

/**
 * 记账工具怎么分给不同的 agent。加一个新工具：写好它，再决定放进哪一组。
 *
 * - 整理（后台）：只管把通知变成流水，看得到最近的账，改不了用户确认过的
 * - 对话：查、随口记、改、加类别；写操作都套上账本锁，和后台整理排队
 */
object LedgerTools {

    /** 账本那把锁的名字。后台整理整轮拿着它；对话里的写工具每次写之前排队拿 */
    const val LOCK = "ledger"

    fun forOrganizer(backend: LedgerBackend, parsedBy: () -> String, zone: () -> ZoneId = { ZoneId.systemDefault() }): List<Tool> =
        listOf(
            RecordExpensesTool(backend, parsedBy, zone),
            ListExpensesTool(backend, zone)
        )

    fun forChat(backend: LedgerBackend, zone: () -> ZoneId = { ZoneId.systemDefault() }): List<Tool> =
        listOf(
            ListExpensesTool(backend, zone),
            AddExpenseTool(backend, zone),
            UpdateExpensesTool(backend, zone),
            AddCategoryTool(backend)
        ).map { LockedTool(it, LOCK) }

    /** 这些工具名是记账的 —— 界面按它决定画记账回执还是提醒卡片 */
    val NAMES = setOf(
        RecordExpensesTool.NAME, ListExpensesTool.NAME, AddExpenseTool.NAME, UpdateExpensesTool.NAME, AddCategoryTool.NAME
    )
}
