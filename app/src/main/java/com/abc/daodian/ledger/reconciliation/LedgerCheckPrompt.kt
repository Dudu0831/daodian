package com.abc.daodian.ledger.reconciliation

import com.abc.daodian.ledger.domain.ExpenseQuery
import com.abc.daodian.ledger.domain.LedgerText
import com.abc.daodian.ledger.domain.TxnState
import com.abc.daodian.ledger.data.LedgerStore
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
            // 「以这份为准」那句是真机上踩出来的：对话里留着账本重建前聊过的几笔，模型认定「已经处理过」一笔没问
            append("每晚对账：下面这几笔是刚从账本里读出来、现在还没认出来的，以这份为准 —— ")
            append("前面对话里就算聊过其中哪笔，也是没落上账，照样要问。用 ask_user 问（一张卡最多 4 笔），每一笔都要问到。")
            if (pending.isNotEmpty()) {
                append("\n\n待确认的流水：")
                pending.forEach { append('\n').append(LedgerText.txn(it, zone)) }
            }
            if (unreadable.isNotEmpty()) {
                append("\n\n正文被系统遮蔽、读不出金额的通知（也用 ask_user 问他那笔是多少钱、是什么，猜不出就不给选项；他说了就用 add_expense 记，raw_ids 填上这条）：")
                unreadable.forEach { append('\n').append(LedgerText.raw(store.noteOf(it), zone)) }
            }
            // 对话 agent 平时看不到类别表：附上，归类时照着写，别另起炉灶
            append("\n\n").append(LedgerText.categoryTree(store.categories()))
        }
    }
}
