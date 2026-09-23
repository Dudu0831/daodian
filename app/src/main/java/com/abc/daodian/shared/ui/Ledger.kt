package com.abc.daodian.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

/*
 * 「一本账」版式的三块料：组标签、一组 = 一张纸、组内行间细线。
 * 设置页和编辑页共用，两页的纸长得一样。设计稿方向 A：
 * https://claude.ai/artifact/UdcBGTTx5quxPfsnR5Akq7
 */

/** 组上方的小标签，大字距（§8.1 第 4 条） */
@Composable
fun LedgerLabel(text: String, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    Text(
        text, style = DaodianType.sectionLabel, color = colors.muted,
        modifier = modifier.padding(start = 4.dp, top = 28.dp, bottom = 10.dp)
    )
}

/** 一组 = 一张纸。圆角 5dp，同卡片（§8.1 第 3 条） */
@Composable
fun LedgerGroup(content: @Composable ColumnScope.() -> Unit) {
    val colors = DaodianColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(5.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(5.dp)),
        content = content
    )
}

/** 组内行间的细线，左边缩进和字对齐，不贯穿到纸边 */
@Composable
fun LedgerRule() {
    val colors = DaodianColors.current
    Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(colors.ruleSoft))
}
