package com.abc.daodian.ledger.check

import com.abc.daodian.harness.builtin.ledger.ExpenseQuery
import com.abc.daodian.harness.builtin.ledger.LedgerText
import com.abc.daodian.harness.builtin.ledger.TxnState
import com.abc.daodian.ledger.LedgerStore
import java.time.ZoneId

/**
 * 对账那一轮的开场：app 替你发起，列出没认出来的几笔，交给对话 agent 去问。
 * 以「每晚对账：」开头 —— 界面据此把它画成分隔线，提示词里也交代了这一轮的规矩（LedgerPrompt.CHAT）。
 */
object LedgerCheckPrompt {

    /** 最近这么多天里读不出金额的通知也拿出来问；更早的问了也想不起来 */
    private const val UNREADABLE_DAYS = 3L

    /** 没有要问的就是 null */
    suspend fun build(store: LedgerStore): String? {
        val zone = ZoneId.systemDefault()
        val pending = store.query(ExpenseQuery(state = TxnState.PENDING, limit = 30)).reversed()
        val since = System.currentTimeMillis() - UNREADABLE_DAYS * 24 * 3600 * 1000
        val unreadable = store.dao.unreadableRaws().filter { it.postTime >= since }
        if (pending.isEmpty() && unreadable.isEmpty()) return null
        return buildString {
            append("每晚对账：下面这几笔没认出来，逐笔简短地问用户（一次问一两笔）。")
            if (pending.isNotEmpty()) {
                append("\n\n待确认的流水：")
                pending.forEach { append('\n').append(LedgerText.txn(it, zone)) }
            }
            if (unreadable.isNotEmpty()) {
                append("\n\n正文被系统遮蔽、读不出金额的通知（问他那笔是多少钱、是什么；他说了就用 add_expense 记，raw_ids 填上这条）：")
                unreadable.forEach { append('\n').append(LedgerText.raw(store.noteOf(it), zone)) }
            }
            // 对话 agent 平时看不到类别表：附上，归类时照着写，别另起炉灶
            append("\n\n").append(LedgerText.categoryTree(store.categories()))
        }
    }
}
