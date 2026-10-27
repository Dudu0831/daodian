package com.abc.daodian.ledger.capture

import android.content.Context
import com.abc.daodian.intake.plugin.Handed
import com.abc.daodian.intake.plugin.NoticeTarget
import com.abc.daodian.intake.plugin.Receipt
import com.abc.daodian.ledger.data.LedgerStore

/**
 * 记账接通知管家交来的通知（DESIGN.md §12.5）：**原样**复制进 `raw_notification`，带上收件箱那一条的 id，
 * 整理员照旧每 3 小时一批整理（§10.1，不变），记好那一笔时回写（[LedgerStore.record]）。
 * 将来取代 [LedgerCapture]（第 8 步）。附言先不给整理员（NOTICE_PLAN 第 10 节第 4 条）
 *
 * 同一条交两次（重交）照指纹只存一次，天然不重复。
 */
object LedgerNoticeTarget : NoticeTarget {

    /** 和 `LedgerModule.ID` 一样。交接表里存它，不改 */
    const val ID = "ledger"

    override val id = ID

    override val label = "记账"

    override val brief = "付款、收款、退款、转账、红包这类动了钱的通知，记成账。附言可以空着"

    override suspend fun accept(context: Context, handed: List<Handed>): List<Receipt> {
        val store = LedgerStore.get(context)
        return handed.map { h ->
            val fresh = store.ingest(h.notice, noticeId = h.noticeId)
            Receipt(h.handoffId, if (fresh) "待整理" else "已经收过")
        }
    }
}
