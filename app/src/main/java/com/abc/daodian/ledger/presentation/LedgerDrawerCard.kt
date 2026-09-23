package com.abc.daodian.ledger.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.ui.DrawerPaper
import com.abc.daodian.shared.ui.DrawerPaperHead
import com.abc.daodian.shared.ui.activityViewModel

/** 抽屉里记账那张纸：这个月支出、收入，一根按类别分段的细条，没认出来的几笔 */
@Composable
fun LedgerDrawerCard(onOpen: () -> Unit) {
    val vm = activityViewModel<LedgerViewModel>()
    val month by vm.thisMonth.collectAsState()
    val checkTime = LedgerFormat.nextCheck(vm.checkTime.collectAsState().value)
    val colors = DaodianColors.current
    DrawerPaper(onOpen) {
        DrawerPaperHead("${month?.period?.kicker ?: "本月"} · 记账", "明细 ›")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("支出", style = DaodianType.caption, color = colors.muted)
                Text(LedgerFormat.yuan(month?.spent ?: 0), style = DaodianType.greeting.copy(fontSize = 27.sp, lineHeight = 32.sp), color = colors.ink)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("收入", style = DaodianType.caption, color = colors.muted)
                Text(LedgerFormat.yuan(month?.income ?: 0), style = DaodianType.cardTitle, color = colors.ink2)
            }
        }
        val slices = month?.spending.orEmpty().filter { it.amount > 0 }
        if (slices.isNotEmpty()) {
            StackedBar(slices)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                slices.take(3).forEach { s ->
                    Text(
                        "${s.name} ${LedgerFormat.money(s.amount).substringBefore('.')}",
                        style = DaodianType.caption,
                        color = if (s.topId == null) colors.muted else colors.ink2, maxLines = 1
                    )
                }
            }
        } else if (month != null) {
            Text("这个月还没有账", style = DaodianType.caption, color = colors.muted)
        }
        if ((month?.pending ?: 0) > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(5.dp).background(colors.accent, CircleShape))
                Text("${month!!.pending} 笔没认出来，$checkTime 问你", style = DaodianType.caption, color = colors.accent)
            }
        }
    }
}

/** 一根细条，按类别分段：前三类墨色深浅，其余并成一段，未归类是虚线框 */
@Composable
private fun StackedBar(slices: List<CategorySlice>) {
    val colors = DaodianColors.current
    val known = slices.filter { it.topId != null }
    val unknown = slices.filter { it.topId == null }.sumOf { it.amount }
    val parts = known.take(3).mapIndexed { i, s -> s.amount to colors.ink.copy(alpha = 1f - i * 0.28f) } +
        listOfNotNull(known.drop(3).sumOf { it.amount }.takeIf { it > 0 }?.let { it to colors.rule2 })
    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (amount, color) ->
            Box(Modifier.weight(amount.toFloat()).fillMaxHeight().background(color, RoundedCornerShape(1.dp)))
        }
        if (unknown > 0) {
            Box(
                Modifier.weight(unknown.toFloat()).fillMaxHeight()
                    .border(1.dp, colors.hint, RoundedCornerShape(1.dp))
            )
        }
    }
}
