package com.abc.daodian.ledger

import android.content.Context
import com.abc.daodian.agent.diagnostics.Diagnostics.time
import com.abc.daodian.agent.diagnostics.rows
import com.abc.daodian.ledger.data.db.LedgerDatabase

/** 「导出诊断」里记账那一段：每个 app 收了几条、整理成没成、账各是什么状态。不写金额、商户、通知原文 */
internal object LedgerDiagnostics {

    fun build(context: Context): String = buildString {
        val db = LedgerDatabase.get(context).openHelper.readableDatabase
        val week = System.currentTimeMillis() - 7 * 24 * 3600_000L

        appendLine("近 7 天抓到的通知（app · 怎么抓的 · 状态 · 条数）：")
        db.rows(
            "SELECT pkg, capturedHow, state, COUNT(*) FROM raw_notification WHERE capturedAt >= $week " +
                "GROUP BY pkg, capturedHow, state ORDER BY pkg"
        ).ifEmpty { listOf(listOf("一条没有", null, null, null)) }.forEach { r ->
            appendLine("  " + r.filterNotNull().joinToString(" · "))
        }
        appendLine("最后一条抓到的：${time(db.rows("SELECT MAX(capturedAt) FROM raw_notification").firstOrNull()?.get(0)?.toLongOrNull())}")

        appendLine("账（状态 · 来路 · 笔数）：" + db.rows("SELECT state, source, COUNT(*) FROM txn GROUP BY state, source")
            .joinToString("、") { "${it[0]}/${it[1]} ${it[2]}" }.ifEmpty { "一笔没有" })

        appendLine("最近整理 / 对账（10 次，种类 · 谁叫的 · 开始 · 用时 · 通知 → 记下 / 不算 / 待问 · 出错）：")
        db.rows(
            "SELECT kind, reason, startedAt, finishedAt, rawCount, recorded, ignored, pending, error FROM agent_run " +
                "ORDER BY id DESC LIMIT 10"
        ).forEach { r ->
            val s = r[2]?.toLongOrNull()
            val took = r[3]?.toLongOrNull()?.let { e -> s?.let { "${(e - it) / 1000}s" } } ?: "没跑完"
            appendLine("  ${r[0]} · ${r[1]} · ${time(s)} · $took · ${r[4]} → ${r[5]} / ${r[6]} / ${r[7]}" +
                (r[8]?.let { " · ${it.take(200)}" } ?: ""))
        }
    }
}
