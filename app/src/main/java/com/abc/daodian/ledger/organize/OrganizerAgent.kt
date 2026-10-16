package com.abc.daodian.ledger.organize

import com.abc.daodian.ledger.domain.LedgerBackend
import com.abc.daodian.ledger.tools.LedgerTools
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.AgentInfo

/**
 * 记账整理员：后台把一批原始通知整理成流水（DESIGN.md §10.1 ②）。用户看不到它说的话，问不了人。
 * 什么时候跑、一批喂多少、拿哪把锁归 [Organizer]；它说什么归 [OrganizerPrompt]。
 */
object OrganizerAgent : AgentInfo {

    override val id = "organizer"
    override val label = "记账整理员"
    override val owner = "ledger"
    override val wakes = "后台：每 3 小时、对账前、手动点「现在整理」"
    override val prompts = listOf(OrganizerPrompt.PLAIN, OrganizerPrompt.TAGGING)

    /** [tagging]：设置里「整理员自己打标签」开没开；[parsedBy] 写进每笔流水，看账知道是谁、哪一版整理的 */
    fun build(store: LedgerBackend, parsedBy: () -> String, tagging: Boolean) =
        Agent(this, OrganizerPrompt.prompt(tagging), LedgerTools.forOrganizer(store, parsedBy, tagging), maxSteps = 5)
}
