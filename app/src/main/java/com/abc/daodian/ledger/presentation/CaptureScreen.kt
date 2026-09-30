package com.abc.daodian.ledger.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.ledger.data.db.RawState
import com.abc.daodian.shared.apps.AppCatalog
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * 抓到的通知（抓取页）：存下来的原文，一条条看，后来进了哪一笔。从记账设置页、记账总览底下「少了一笔？」进。
 * 监听本身（使用权、连没连着、重连、扫通知栏）归通知监听层，在「权限与监听」页（DESIGN.md §2.3）；
 * 这里不放通知的行、不跳过去，也没有「现在抓一下」—— 连上时、解锁时、整理前监听层自己会扫。
 *
 * 调研时有过一个临时采样页，记账转正时删了；09-24 用户发现通知还是会漏抓，放回来。
 * 为什么会漏见 DESIGN.md §2.3：实时回调会丢（荣耀冻进程），监听也会断（系统不一定绑回来）。
 * 这一页不改账 —— 抓到的原文进「待整理」，整理了才进账。
 */

@Composable
fun CaptureScreen(vm: CaptureViewModel, onBack: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val colors = DaodianColors.current
    val rows by vm.rows.collectAsState()
    val total by vm.total.collectAsState()
    val pending by vm.pending.collectAsState()
    val organizing by vm.organizing.collectAsState()
    var expanded by remember { mutableStateOf(emptySet<Long>()) }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("抓到的通知", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item(key = "pending") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    PaperGroup {
                        SettingRow(
                            title = if (pending > 0) "待整理 $pending 条" else "没有待整理的",
                            note = if (organizing) "模型在读通知，读完了进账" else "抓到的先放在这，整理了才进账",
                            onClick = if (pending > 0 && !organizing) ({ vm.organizeNow() }) else null
                        ) {
                            if (pending > 0 && !organizing) Text("现在整理 ›", style = DaodianType.caption, color = colors.accent)
                        }
                    }
                }
            }

            item(key = "head") {
                SectionLabel(
                    if (total > CaptureViewModel.LIMIT) "最近 ${CaptureViewModel.LIMIT} 条 · 一共 $total 条" else "一共 $total 条",
                    Modifier.padding(horizontal = Gutter).padding(top = 30.dp, bottom = 2.dp)
                )
            }

            val list = rows
            if (list != null && list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没抓到过。付一笔钱试试。",
                        style = DaodianType.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp)
                    )
                }
            }
            var lastDay: LocalDate? = null
            list.orEmpty().forEach { row ->
                val day = dayOf(row.raw.postTime)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") {
                        Text(
                            LedgerFormat.dayHeader(day),
                            style = DaodianType.caption,
                            color = colors.muted,
                            modifier = Modifier.padding(horizontal = Gutter).padding(top = 16.dp, bottom = 2.dp)
                        )
                    }
                }
                item(key = row.raw.id) {
                    val id = row.raw.id
                    RawRow(
                        row = row,
                        expanded = id in expanded,
                        onToggle = { expanded = if (id in expanded) expanded - id else expanded + id },
                        onOpenTxn = onOpenTxn
                    )
                }
            }
            if (!list.isNullOrEmpty()) {
                item(key = "foot") { Footnote("点一条看全文、抓到的时刻 · 原始通知永远不删") }
            }
        }
    }
}

/** 一条原始通知：哪家、几点、怎么抓到的、原文；右边是它后来怎样了。点开看全文和抓到的时刻 */
@Composable
private fun RawRow(row: CapturedRow, expanded: Boolean, onToggle: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val r = row.raw
    val faded = r.state == RawState.SUPERSEDED || r.state == RawState.IGNORED
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    AppCatalog.label(context, r.pkg),
                    Format.clock(r.postTime),
                    LedgerFormat.capturedHow(r.capturedHow),
                    "被系统遮蔽".takeIf { r.redacted }
                ).joinToString(" · "),
                style = DaodianType.basis,
                color = colors.hint,
                modifier = Modifier.weight(1f)
            )
            StateTag(row, onOpenTxn)
        }
        Text(
            listOfNotNull(r.title, r.text).filter { it.isNotBlank() }.joinToString("｜").ifEmpty { "（空通知）" },
            style = DaodianType.bodySmall.copy(lineHeight = 20.sp),
            color = if (faded) colors.hint else colors.ink2,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
        if (expanded) {
            r.extra?.let { Text(it, style = DaodianType.caption, color = colors.muted) }
            Text(
                "通知发出 ${LedgerFormat.clockSeconds(r.postTime)} · 抓到 ${LedgerFormat.recentSeconds(r.capturedAt)}" +
                    "（晚 ${LedgerFormat.lag(r.capturedAt - r.postTime)}）",
                style = DaodianType.caption,
                color = colors.hint
            )
            r.stateNote?.let { Text(it, style = DaodianType.caption, color = colors.hint) }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = Gutter), color = colors.ruleSoft)
}

/** 右上角：进了哪一笔（能点）、待整理、不是账、看不清、换成全文了 */
@Composable
private fun StateTag(row: CapturedRow, onOpenTxn: (Long) -> Unit) {
    val colors = DaodianColors.current
    val txn = row.txnId
    if (txn != null) {
        Text(
            "进了 #$txn ›",
            style = DaodianType.caption,
            color = colors.accent,
            modifier = Modifier.clickable { onOpenTxn(txn) }.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
        )
        return
    }
    when (row.raw.state) {
        RawState.PENDING -> Text(
            "待整理",
            style = DaodianType.badge.copy(fontSize = 10.5.sp),
            color = colors.accent,
            modifier = Modifier
                .padding(start = 12.dp)
                .border(1.dp, colors.accent, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp)
        )
        RawState.UNREADABLE -> Text("看不清", style = DaodianType.caption, color = colors.red, modifier = Modifier.padding(start = 12.dp))
        RawState.IGNORED -> Text("不是账", style = DaodianType.caption, color = colors.hint, modifier = Modifier.padding(start = 12.dp))
        RawState.SUPERSEDED -> Text("换成全文了", style = DaodianType.caption, color = colors.hint, modifier = Modifier.padding(start = 12.dp))
        RawState.DONE -> Text("已整理", style = DaodianType.caption, color = colors.hint, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
