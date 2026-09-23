package com.abc.daodian.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.harness.builtin.ledger.Actor
import com.abc.daodian.harness.builtin.ledger.Direction
import com.abc.daodian.harness.builtin.ledger.ListExpensesTool
import com.abc.daodian.harness.builtin.ledger.TxnBrief
import com.abc.daodian.harness.builtin.ledger.TxnSource
import com.abc.daodian.harness.builtin.ledger.TxnState
import com.abc.daodian.ledger.PaySources
import com.abc.daodian.ui.common.BackIcon
import com.abc.daodian.ui.common.IconTapTarget
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/*
 * 记账三层（设计稿：<https://claude.ai/artifact/PRk3CWeu24V4tKZgxkGLwn> 第二排）：
 *   ① 总览：日 / 月 / 季 / 年，支出、收入、柱子、按一级类别
 *   ② 一个类别：再细一层（二级），和这一类的每一笔
 *   ③ 一笔：全部字段、原始通知原文（依据）、改动记录
 * 都只看不改 —— 要改在对话里说，每一层都有路回对话。
 */

private val Gutter = 26.dp

// ---------------- 共用小零件 ----------------

@Composable
private fun LedgerTopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val colors = DaodianColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTapTarget(onClick = onBack) { BackIcon(tint = colors.ink2) }
            Text(title, style = DaodianType.screenTitle, color = colors.ink)
        }
        trailing()
    }
}

@Composable
private fun Kicker(text: String) {
    Text(text, style = DaodianType.sectionLabel, color = DaodianColors.current.muted)
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DaodianType.sectionLabel.copy(fontSize = 11.sp), color = DaodianColors.current.hint, modifier = modifier)
}

@Composable
private fun BigAmount(text: String, size: Int = 44) {
    Text(text, style = DaodianType.greeting.copy(fontSize = size.sp, lineHeight = (size * 1.15).sp), color = DaodianColors.current.ink)
}

/** 一行：名字、细墨条（按最大那行的比例）、金额、› */
@Composable
private fun SliceRow(name: String, amount: Long, fraction: Float, dashed: Boolean, onClick: (() -> Unit)?) {
    val colors = DaodianColors.current
    val ink = if (dashed) colors.muted else colors.ink
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Gutter, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(name, style = DaodianType.bodySmall, color = ink, maxLines = 1, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(5.dp)) {
            if (fraction > 0f) {
                val shape = RoundedCornerShape(1.dp)
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.012f, 1f))
                        .fillMaxHeight()
                        .then(if (dashed) Modifier.border(1.dp, colors.hint, shape) else Modifier.background(colors.ink, shape))
                )
            }
        }
        Text(
            LedgerFormat.money(amount), style = DaodianType.cardTitle.copy(fontSize = 14.sp), color = ink,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 64.dp)
        )
        Text(if (onClick != null) "›" else " ", style = DaodianType.bodySmall, color = colors.rule2)
    }
}

/** 流水一行：摘要 + 细节一行（时刻 · 类别 · 卡），右边金额。待确认的挂朱砂小框 */
@Composable
private fun TxnRow(t: TxnBrief, showDay: Boolean, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val pending = t.state == TxnState.PENDING
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Gutter, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                t.summary, style = DaodianType.body, color = if (pending) colors.muted else colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pending) PendingBadge()
                Text(
                    listOfNotNull(
                        if (showDay) LedgerFormat.dayTime(t.occurredAt) else LedgerFormat.dayTime(t.occurredAt).substringAfter(' '),
                        t.category?.substringAfter('/')?.takeIf { !pending },
                        t.account ?: t.channel,
                        "你说的".takeIf { t.state == TxnState.CONFIRMED }
                    ).joinToString(" · "),
                    style = DaodianType.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            LedgerFormat.signed(t.direction, t.amount),
            style = DaodianType.cardTitle.copy(fontSize = 16.sp),
            color = if (t.direction == Direction.OUT) colors.ink else colors.ink2
        )
        Text("›", style = DaodianType.bodySmall, color = colors.rule2)
    }
    HorizontalDivider(Modifier.padding(horizontal = Gutter), color = colors.ruleSoft)
}

@Composable
private fun PendingBadge() {
    val colors = DaodianColors.current
    Text(
        "待确认",
        style = DaodianType.badge.copy(fontSize = 10.5.sp),
        color = colors.accent,
        modifier = Modifier.border(1.dp, colors.accent, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 0.dp)
    )
}

/** 只读页唯一的「动作」：去对话里说。朱砂淡底的一条 */
@Composable
private fun ToChatBand(text: String, action: String, onClick: () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(colors.red.copy(alpha = 0.06f), shape)
            .border(1.dp, colors.accent.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(5.dp).background(colors.accent, CircleShape))
        Text(text, style = DaodianType.bodySmall, color = colors.ink2, modifier = Modifier.weight(1f))
        Text(action, style = DaodianType.bodySmall, color = colors.accent)
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text, style = DaodianType.caption, color = DaodianColors.current.hint, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 28.dp)
    )
}

// ---------------- ① 总览 ----------------

@Composable
fun LedgerOverviewScreen(
    vm: LedgerViewModel,
    checkTime: String,
    onBack: () -> Unit,
    onOpenCategory: (topId: Long, income: Boolean, period: Period) -> Unit,
    onCheckNow: () -> Unit
) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val o by vm.overview.collectAsState()
    val period by vm.period.collectAsState()
    var showIncome by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        LedgerTopBar("记账", onBack) {
            PeriodSwitch(period.mode) { mode -> vm.setPeriod(Period(mode, if (mode == period.mode) period.anchor else java.time.LocalDate.now())) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹", style = DaodianType.screenTitle, color = colors.muted,
                    modifier = Modifier.clickable { vm.setPeriod(period.shift(-1)) }.padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(period.label, style = DaodianType.cardTitle.copy(fontSize = 15.sp), color = colors.ink)
                val canForward = !period.isCurrentOrLater
                Text(
                    "›", style = DaodianType.screenTitle, color = if (canForward) colors.muted else colors.rule,
                    modifier = Modifier
                        .then(if (canForward) Modifier.clickable { vm.setPeriod(period.shift(1)) } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            val data = o?.takeIf { it.period == period }
            Column(Modifier.padding(horizontal = Gutter).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Kicker("${period.kicker} · ${if (showIncome) "收入" else "支出"}")
                BigAmount(LedgerFormat.yuan(if (showIncome) data?.income ?: 0 else data?.spent ?: 0))
                val first = data?.firstDay
                val note = when {
                    first != null && first > period.from && first <= period.to ->
                        Period.dateOf(first).let { "${it.monthValue}月${it.dayOfMonth}日开始记" }
                    else -> "${data?.count ?: 0} 笔"
                }
                Row {
                    Text(
                        if (showIncome) "支出 ${LedgerFormat.yuan(data?.spent ?: 0)} ›" else "收入 ${LedgerFormat.yuan(data?.income ?: 0)} ›",
                        style = DaodianType.bodySmall, color = colors.ink2,
                        modifier = Modifier.clickable { showIncome = !showIncome }
                    )
                    Text("　·　$note", style = DaodianType.bodySmall, color = colors.hint)
                }
            }

            if (data != null && data.bars.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Bars(data.bars, period.mode) { vm.setPeriod(it) }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(if (showIncome) "钱从哪来" else "花在哪", Modifier.padding(horizontal = Gutter, vertical = 4.dp))
            val slices = (if (showIncome) data?.incomes else data?.spending).orEmpty()
            val max = slices.maxOfOrNull { kotlin.math.abs(it.amount) }?.takeIf { it > 0 } ?: 1
            if (slices.isEmpty() && data != null) {
                Text(
                    if (!PaySources.granted(context)) "没开通知使用权，记不了账 —— 设置里打开" else "这段时间还没有账",
                    style = DaodianType.bodySmall, color = colors.muted,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 10.dp)
                )
            }
            slices.forEach { s ->
                SliceRow(
                    name = s.name, amount = s.amount, fraction = s.amount.toFloat() / max, dashed = s.topId == null,
                    onClick = { onOpenCategory(s.topId ?: ListExpensesTool.UNCATEGORIZED, showIncome, period) }
                )
            }

            if ((data?.pending ?: 0) > 0) {
                Spacer(Modifier.height(20.dp))
                ToChatBand("${data!!.pending} 笔没认出来，$checkTime 问你", "现在就说 ›", onCheckNow)
            }
            Footnote("点类别看里面每一笔 · 点柱子进到那一段")
        }
    }
}

/** 「日 月 季 年」四个钮，选中的是实心墨块 */
@Composable
private fun PeriodSwitch(selected: PeriodMode, onPick: (PeriodMode) -> Unit) {
    val colors = DaodianColors.current
    Row(
        Modifier.border(1.dp, colors.rule, RoundedCornerShape(16.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PeriodMode.entries.forEach { m ->
            val on = m == selected
            Box(
                Modifier
                    .height(28.dp)
                    .widthIn(min = 34.dp)
                    .background(if (on) colors.solid else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onPick(m) },
                contentAlignment = Alignment.Center
            ) {
                Text(m.label, style = DaodianType.bodySmall, color = if (on) colors.onSolid else colors.ink2)
            }
        }
    }
}

/** 柱子：月是每天一根，季 / 年是每月一根。还没开始记的画一道淡线，将来的空着 */
@Composable
private fun Bars(bars: List<Bar>, mode: PeriodMode, onPick: (Period) -> Unit) {
    val colors = DaodianColors.current
    val max = bars.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1L
    val barWidth = when (mode) {
        PeriodMode.QUARTER -> 32.dp
        PeriodMode.YEAR -> 14.dp
        else -> null
    }
    val gap = if (mode == PeriodMode.MONTH) 2.dp else 4.dp
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(if (mode == PeriodMode.MONTH) "每天" else "每月")
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            bars.forEach { b ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (b.period != null && b.amount != 0L) Modifier.clickable { onPick(b.period) } else Modifier),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val w = barWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()
                    when {
                        b.future -> Unit
                        b.beforeData -> Box(w.height(1.dp).background(colors.rule))
                        b.amount > 0 -> Box(
                            w.height((64f * b.amount / max).coerceAtLeast(2f).dp)
                                .background(colors.ink, RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                        )
                        else -> Box(w.height(1.dp).background(colors.ruleSoft))
                    }
                }
            }
        }
        HorizontalDivider(color = colors.rule)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            // 每格只有 1/30 宽，字比格子宽：让字越出格子居中画，不截断
            bars.forEach { b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        b.label, style = DaodianType.caption.copy(fontSize = 10.sp), color = colors.hint,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.wrapContentWidth(unbounded = true)
                    )
                }
            }
        }
    }
}

// ---------------- ② 一个类别 ----------------

@Composable
fun LedgerCategoryScreen(
    vm: LedgerViewModel,
    topId: Long,
    income: Boolean,
    period: Period,
    onBack: () -> Unit,
    onOpenTxn: (Long) -> Unit
) {
    val colors = DaodianColors.current
    val flow = remember(topId, income, period) { vm.category(topId, income, period) }
    val detail by flow.collectAsState(initial = null)
    val d = detail

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        LedgerTopBar(d?.name ?: "", onBack) {
            Text(period.label, style = DaodianType.bodySmall, color = colors.muted)
        }
        if (d == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = Gutter).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Kicker("${period.kicker} · ${d.name}")
                BigAmount(LedgerFormat.yuan(d.total), size = 40)
                Text(
                    "占这段${if (income) "收入" else "支出"} ${(d.share * 100).toInt()}%　·　${d.txns.size} 笔",
                    style = DaodianType.bodySmall, color = colors.ink2
                )
            }

            // 只有一个叶子、而且就是这个一级自己时，「再细一层」没意义
            val leaves = d.leaves
            if (leaves.size > 1 || (leaves.size == 1 && leaves[0].topId != topId)) {
                Spacer(Modifier.height(24.dp))
                SectionLabel("再细一层", Modifier.padding(horizontal = Gutter, vertical = 4.dp))
                val max = leaves.maxOf { kotlin.math.abs(it.amount) }.coerceAtLeast(1)
                leaves.forEach { l -> SliceRow(l.name, l.amount, l.amount.toFloat() / max, dashed = false, onClick = null) }
            }

            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel("每一笔")
                SectionLabel("新的在上")
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(Modifier.padding(horizontal = Gutter), color = colors.rule)
            d.txns.forEach { t -> TxnRow(t, showDay = true) { onOpenTxn(t.id) } }
            Footnote("这页只看不改 · 要改哪笔，去对话里说一声")
        }
    }
}

// ---------------- ③ 一笔 ----------------

@Composable
fun LedgerTxnScreen(
    vm: LedgerViewModel,
    txnId: Long,
    onBack: () -> Unit,
    onTalk: (String) -> Unit
) {
    val colors = DaodianColors.current
    val flow = remember(txnId) { vm.txn(txnId) }
    val detail by flow.collectAsState(initial = null)
    val d = detail

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        LedgerTopBar("", onBack)
        if (d == null) return@Column
        val t = d.txn
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Kicker(t.direction.label + if (t.state == TxnState.VOID) " · 已作废" else "")
                BigAmount(LedgerFormat.yuan(t.amount), size = 40)
                Text(t.summary, style = DaodianType.cardTitle.copy(fontSize = 17.sp), color = colors.ink)
                if (t.state == TxnState.PENDING && t.ask != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PendingBadge()
                        Text(t.ask, style = DaodianType.caption, color = colors.muted)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            val shape = RoundedCornerShape(5.dp)
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(colors.surface, shape)
                    .border(1.dp, colors.rule, shape)
                    .padding(horizontal = 18.dp, vertical = 4.dp)
            ) {
                val rows = listOfNotNull(
                    "时间" to LedgerFormat.dayWeekTime(t.occurredAt),
                    "类别" to (t.category?.replace("/", " › ") ?: "未归类"),
                    (t.merchant ?: t.merchantRaw)?.let { "商户" to it },
                    listOfNotNull(t.account, t.channel?.let { "走$it" }).joinToString(" · ").ifEmpty { null }?.let { "付款" to it },
                    "谁归的" to whoOf(t),
                    t.tags.takeIf { it.isNotEmpty() }?.let { "标签" to it.joinToString("、") },
                    t.note?.let { "备注" to it },
                    t.refundOf?.let { "退的是" to "#$it" }
                )
                rows.forEachIndexed { i, (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(k, style = DaodianType.bodySmall, color = colors.muted, modifier = Modifier.width(52.dp))
                        Text(v, style = DaodianType.bodySmall, color = colors.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                    if (i < rows.lastIndex) HorizontalDivider(color = colors.ruleSoft)
                }
            }

            if (d.raws.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Column(Modifier.padding(horizontal = Gutter).fillMaxWidth()) {
                    SectionLabel("依据" + if (d.raws.size > 1) " · ${d.raws.size} 条通知合成一笔" else "")
                    Spacer(Modifier.height(8.dp))
                    d.raws.forEach { r ->
                        Row(Modifier.padding(bottom = 10.dp).height(IntrinsicSize.Min)) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule2))
                            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${r.source} ${LedgerFormat.dayTime(r.postTime).substringAfter(' ')}", style = DaodianType.basis, color = colors.hint)
                                Text(
                                    listOfNotNull(r.title, r.text).joinToString("｜"),
                                    style = DaodianType.basis.copy(lineHeight = 17.sp), color = colors.muted
                                )
                            }
                        }
                    }
                }
            } else if (t.source == TxnSource.CHAT) {
                Spacer(Modifier.height(18.dp))
                Text("你在对话里说的，没有通知", style = DaodianType.caption, color = colors.hint, modifier = Modifier.padding(horizontal = Gutter))
            }

            Spacer(Modifier.height(14.dp))
            val changes = d.changes
            Text(
                if (changes.isEmpty()) "没改过" else "改过 ${changes.size} 处",
                style = DaodianType.caption, color = colors.hint, modifier = Modifier.padding(horizontal = Gutter)
            )
            changes.takeLast(6).forEach { c ->
                Text(
                    "${LedgerFormat.dayTime(c.at)} ${if (c.actor == Actor.USER) "你" else if (c.actor == Actor.MODEL) "模型" else "app"} · ${fieldName(c.field)}",
                    style = DaodianType.caption, color = colors.hint,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(colors.solid, RoundedCornerShape(25.dp))
                    .clickable { onTalk("关于 #${t.id}（${t.summary} ${LedgerFormat.money(t.amount)}）：") },
                contentAlignment = Alignment.Center
            ) {
                Text("这笔不对？去对话里说", style = DaodianType.button, color = colors.onSolid)
            }
        }
    }
}

private fun whoOf(t: TxnBrief): String = when {
    t.source == TxnSource.CHAT -> "你在对话里记的"
    t.state == TxnState.CONFIRMED -> "你确认过"
    t.state == TxnState.PENDING -> "还没认出来，等你说"
    t.state == TxnState.VOID -> "作废了"
    else -> "模型自己归的"
}

private fun fieldName(f: String) = when (f) {
    "allocation" -> "类别"
    "state" -> "状态"
    "amount" -> "金额"
    "summary" -> "摘要"
    "note" -> "备注"
    "merchant" -> "商户"
    "merchant_memory" -> "记住了这家"
    "tags" -> "标签"
    "refund_of" -> "挂上退款"
    "raws" -> "通知"
    "direction" -> "方向"
    else -> f
}
