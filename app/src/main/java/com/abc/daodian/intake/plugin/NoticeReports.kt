package com.abc.daodian.intake.plugin

import android.content.Context
import com.abc.daodian.intake.data.IntakeDatabase

/**
 * 模块往收件箱写回执：交接那一行上的「变成了什么」（DESIGN.md §12.5）。
 * 当场办完的随 [Receipt] 回去就行；后来才办完的（记账整理完那一笔）、怕进程被杀丢了的（建提醒），走这里
 */
object NoticeReports {

    /** 这次交接的回执。写几次都行，后写的算 */
    suspend fun report(context: Context, handoffId: Long, text: String, route: String? = null) {
        dao(context).report(handoffId, text, route, System.currentTimeMillis())
    }

    /** 这次交接有没有回执了：重交时先问一声，办过的别再办 */
    suspend fun receiptOf(context: Context, handoffId: Long): String? = dao(context).receiptOf(handoffId)

    /** 只知道收件箱那一条（记账整理完时手上只有原文上的 noticeId）：补在交给 [target] 的最近一次交接上 */
    suspend fun reportNotice(context: Context, noticeId: Long, target: String, text: String, route: String? = null) {
        dao(context).reportLatest(noticeId, target, text, route, System.currentTimeMillis())
    }

    private fun dao(context: Context) = IntakeDatabase.get(context).dao()
}
