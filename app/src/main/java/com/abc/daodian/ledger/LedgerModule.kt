package com.abc.daodian.ledger

import android.content.Context
import com.abc.daodian.ledger.assistant.LedgerSkill
import com.abc.daodian.ledger.organize.OrganizeWorker
import com.abc.daodian.ledger.presentation.LedgerShell
import com.abc.daodian.ledger.reconciliation.LedgerCheck
import com.abc.daodian.module.Module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 记账的接头。记账自己在后台转：存通知（capture，通知是监听层分过来的）、定期整理（organize）、每晚对账（reconciliation）；
 * 这里把它交出去：给助手的（[LedgerSkill]）、给壳的（[LedgerShell]），冷启动排上整理和对账。
 */
object LedgerModule : Module {

    const val ID = "ledger"
    const val LABEL = "记账"

    override val id = ID
    override val label = LABEL
    override val assistant get() = LedgerSkill
    override val shell get() = LedgerShell

    override fun onAppStart(context: Context) {
        val app = context.applicationContext
        // 排上定期整理、排上每晚对账
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { OrganizeWorker.schedule(app) }
            runCatching { LedgerCheck.arm(app) }
        }
    }
}
