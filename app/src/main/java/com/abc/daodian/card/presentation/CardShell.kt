package com.abc.daodian.card.presentation

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.navigation.NavGraphBuilder
import com.abc.daodian.card.CardHub
import com.abc.daodian.card.CardModule
import com.abc.daodian.card.CardReader
import com.abc.daodian.card.Cards
import com.abc.daodian.card.spec.CardSize
import com.abc.daodian.card.spec.CardSpecs
import com.abc.daodian.card.spec.Ref
import com.abc.daodian.shell.entry.AppNav
import com.abc.daodian.shell.entry.ShellEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面卡片给壳的：只有「导出诊断」里一段。没有自己的页面 —— 卡片在对话里画、在桌面上看。见 DESIGN.md §8.5
 *
 * 只写状态和数（§11）：画过几张、桌面上几块、每张多大、绑了哪几类、绑的那条没了没有、有几个名字取不到值。
 * 卡片的名字、说明原文不写。「取不到」多半是模块改了名字、卡片说明没跟上（或者此刻就是没有，比如今天没有下一条）。
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
                    appendLine("  c${c.id} ${c.size} · 说明读不出（旧的零件拼法）")
                    return@forEach
                }
                val reader = CardReader(context, CardHub.sources)
                val refs = CardSpecs.refs(spec.html)
                val gone = reader.gone(refs + CardSpecs.taps(spec.html).mapNotNull(Ref::parse))
                val missing = refs.count { reader.value(it) == null }
                appendLine(
                    "  c${c.id} ${spec.size.label} · ${if (c.widgetId != null) "在桌面上" else "没放"} · 网页 ${spec.html.length} 字 · " +
                        "绑了 ${refs.map { it.source }.distinct().joinToString("、").ifEmpty { "无" }}" +
                        (gone?.let { " · 绑的那条${it}没了" } ?: if (missing > 0) " · $missing 个取不到" else "")
                )
            }
        }.trimEnd()
    }
}
