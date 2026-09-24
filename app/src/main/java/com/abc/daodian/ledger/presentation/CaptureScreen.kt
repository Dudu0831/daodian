package com.abc.daodian.ledger.presentation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abc.daodian.ledger.capture.PaySources
import com.abc.daodian.ledger.data.db.RawState
import com.abc.daodian.shared.format.Format
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.FixLink
import com.abc.daodian.shared.ui.GroupRule
import com.abc.daodian.shared.ui.Marker
import com.abc.daodian.shared.ui.PaperGroup
import com.abc.daodian.shared.ui.ScreenTopBar
import com.abc.daodian.shared.ui.SettingRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * 抓到的通知（抓取页）：监听连没连着、手动抓一下、存下来的原文，一条条看。
 *
 * 调研时有过一个临时采样页，记账转正时删了；09-24 用户发现通知还是会漏抓，放回来。
 * 为什么会漏见 LEDGER_PLAN.md §2.3：实时回调会丢（荣耀冻进程），监听也会断（系统不一定绑回来）。
 * 这一页不改账 —— 抓到的原文进「待整理」，整理了才进账。
 */

@Composable
fun CaptureScreen(vm: CaptureViewModel, onBack: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val colors = DaodianColors.current
    val context = LocalContext.current
    val listener by vm.listener.collectAsState()
    val rows by vm.rows.collectAsState()
    val total by vm.total.collectAsState()
    val pending by vm.pending.collectAsState()
    val lastPosted by vm.lastPosted.collectAsState()
    val organizing by vm.organizing.collectAsState()
    val action by vm.action.collectAsState()
    var granted by remember { mutableStateOf(PaySources.granted(context)) }
    // 从系统设置开完通知使用权回来，当场变
    LifecycleResumeEffect(Unit) {
        granted = PaySources.granted(context)
        onPauseOrDispose { }
    }
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    val busy = action == CaptureAction.Grabbing || action == CaptureAction.Reconnecting

    fun openGrant() {
        runCatching { context.startActivity(PaySources.grantIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        ScreenTopBar("抓到的通知", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item(key = "status") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    PaperGroup {
                        SettingRow(
                            title = "通知使用权",
                            note = if (granted) null else "没开，一条都抓不到 —— 点这里去系统设置里打开「到点」",
                            noteColor = colors.red,
                            onClick = ::openGrant,
                            leading = { Marker(granted) }
                        ) {
                            if (granted) Text("开着", style = DaodianType.settingValue, color = colors.muted) else FixLink()
                        }
                        if (granted) {
                            GroupRule()
                            SettingRow(
                                title = "监听",
                                note = listenerNote(listener),
                                noteColor = if (listener.connected) colors.muted else colors.red,
                                leading = { Marker(listener.connected) }
                            ) {
                                Text(
                                    if (action == CaptureAction.Reconnecting) "在重连……" else "重连",
                                    style = DaodianType.caption,
                                    color = if (busy) colors.hint else colors.accent,
                                    modifier = Modifier
                                        .clickable(enabled = !busy) { vm.reconnect() }
                                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                                )
                            }
                            GroupRule()
                            SettingRow(
                                title = "最近一次实时收到",
                                note = lastPosted?.let { "${LedgerFormat.recent(it)} · 比这晚付的钱没进来，就是实时回调漏了，手动抓一下" }
                                    ?: "还没有 —— 付一笔钱，这里应该当场变",
                                leading = { NoMarker() }
                            )
                        }
                        GroupRule()
                        SettingRow(
                            title = if (pending > 0) "待整理 $pending 条" else "没有待整理的",
                            note = if (organizing) "模型在读通知，读完了进账" else "抓到的先放在这，整理了才进账",
                            onClick = if (pending > 0 && !organizing) ({ vm.organizeNow() }) else null,
                            leading = { NoMarker() }
                        ) {
                            if (pending > 0 && !organizing) Text("现在整理 ›", style = DaodianType.caption, color = colors.accent)
                        }
                    }
                }
            }

            item(key = "grab") {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .alpha(if (busy) 0.55f else 1f)
                            .background(colors.solid, RoundedCornerShape(25.dp))
                            .clickable(enabled = !busy) { if (granted) vm.grab() else openGrant() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                !granted -> "先去开通知使用权"
                                action == CaptureAction.Grabbing -> "在抓……"
                                else -> "现在抓一下"
                            },
                            style = DaodianType.button,
                            color = colors.onSolid
                        )
                    }
                    ActionResult(action, onFix = ::openGrant)
                    Text(
                        "只抓得到还挂在通知栏里的（${PaySources.names}）；从通知栏划掉了的，系统不留，抓不回来。",
                        style = DaodianType.caption,
                        color = colors.hint,
                        modifier = Modifier.padding(horizontal = 10.dp).padding(top = 8.dp)
                    )
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
                        "还没抓到过。付一笔钱试试，或者点上面「现在抓一下」。",
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

/** 「连着 · 今天 09:12 起」「断了 · 今天 10:30」「这次打开 app 以来还没连上过」 */
private fun listenerNote(l: PaySources.Listener): String = when {
    l.connected -> "连着" + (l.since?.let { " · ${LedgerFormat.recent(it)} 起" } ?: "")
    l.since != null -> "断了 · ${LedgerFormat.recent(l.since)} —— 点「重连」，或者直接「现在抓一下」"
    else -> "这次打开 app 以来还没连上过 —— 点「重连」，或者直接「现在抓一下」"
}

/** 按钮下面一行：刚才抓 / 重连的结果 */
@Composable
private fun ActionResult(action: CaptureAction, onFix: () -> Unit) {
    val colors = DaodianColors.current
    val (text, failed) = when (action) {
        is CaptureAction.Grabbed -> {
            val what = when {
                action.seen == 0 && action.saved == 0 -> "通知栏里没有这几家的通知"
                action.saved == 0 -> "通知栏里挂着 ${action.seen} 条，早就都存下了"
                else -> "通知栏里挂着 ${action.seen} 条，新存 ${action.saved} 条"
            }
            (if (action.reconnected) "监听断了，重连上了。" else "") + "${Format.clock(action.at)} 抓了一次：$what" to false
        }
        is CaptureAction.Reconnected -> "${Format.clock(action.at)} 重连上了" to false
        is CaptureAction.NotConnected ->
            "${Format.clock(action.at)} 监听没连上，系统不肯把它绑回来。去系统设置把「到点」的通知使用权关掉再打开 ›" to true
        else -> return
    }
    Text(
        text,
        style = DaodianType.caption,
        color = if (failed) colors.red else colors.ink2,
        modifier = Modifier
            .then(if (failed) Modifier.clickable(onClick = onFix) else Modifier)
            .padding(horizontal = 10.dp)
            .padding(top = 12.dp)
    )
}

/** 一条原始通知：哪家、几点、怎么抓到的、原文；右边是它后来怎样了。点开看全文和抓到的时刻 */
@Composable
private fun RawRow(row: CapturedRow, expanded: Boolean, onToggle: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val colors = DaodianColors.current
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
                    PaySources.nameOf(r.pkg),
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

/** 没有状态可标的行，占住记号那一格，标题和上面几行对齐 */
@Composable
private fun NoMarker() {
    Box(Modifier.size(16.dp))
}

private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
