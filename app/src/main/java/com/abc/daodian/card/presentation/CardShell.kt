package com.abc.daodian.card.presentation

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.navigation.NavGraphBuilder
import com.abc.daodian.card.CardHub
import com.abc.daodian.card.CardModule
import com.abc.daodian.card.CardReader
import com.abc.daodian.card.Cards
import com.abc.daodian.card.own.OwnData
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.bindings
import com.abc.daodian.card.spec.count
import com.abc.daodian.shell.entry.AppNav
import com.abc.daodian.shell.entry.ShellEntry
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面卡片给壳的：只有「导出诊断」里一段。没有自己的页面 —— 卡片在对话里画、在桌面上看。见 DESIGN.md §8.5
 *
 * 只写状态和数（§11）：画过几张、桌面上几块、每张多大、绑了哪几类、有几个名字取不到值。卡片的名字、说明原文、记着的数都不写。
 * 「取不到」多半是钉的那条提醒删了，或者模块改了名字、卡片说明没跟上。
 */
object CardShell : ShellEntry {

    override val id = CardModule.ID

    override val label = CardModule.LABEL

    override fun NavGraphBuilder.routes(nav: AppNav) {}

    override suspend fun diagnostics(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            val manager = AppWidgetManager.getInstance(context)
            val cards = Cards.all(context)
            val onDesk = CardSize.entries.associateWith { manager.getAppWidgetIds(ComponentName(context, Cards.providerOf(it))).size }
            appendLine("画过 ${cards.size} 张，登记在桌面上 ${cards.count { it.widgetId != null }} 张")
            appendLine("桌面上的块：" + onDesk.entries.joinToString("、") { (s, n) -> "${s.label} $n" })
            appendLine("能绑的：" + CardHub.sources.joinToString("、") { it.id })
            cards.forEach { c ->
                val spec = CardSpecs.read(c.spec)
                if (spec == null) {
                    appendLine("  c${c.id} ${c.size} · 说明读不出")
                    return@forEach
                }
                val reader = CardReader(context, CardHub.sources, OwnData(spec.own, c.own, LocalDate.now(), System.currentTimeMillis()))
                val refs = spec.body.bindings().distinctBy { it.toString() }
                val missing = refs.count { reader.value(it) == null }
                appendLine(
                    "  c${c.id} ${spec.size.label} · ${if (c.widgetId != null) "在桌面上" else "没放"} · 零件 ${spec.body.count()} 个 · " +
                        "绑了 ${refs.map { it.source }.distinct().joinToString("、").ifEmpty { "无" }}" +
                        if (missing > 0) " · $missing 个取不到" else ""
                )
            }
        }.trimEnd()
    }
}
