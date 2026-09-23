package com.abc.daodian.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

/*
 * 抽屉里的一张纸（设计稿方向 B「两张纸」：<https://claude.ai/artifact/PRk3CWeu24V4tKZgxkGLwn>）。
 * 每个模块在抽屉里放一张，长得一样：外框 + 一行抬头（左边是什么、右边往哪去）+ 自己的内容。
 */

@Composable
fun DrawerPaper(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = DaodianColors.current
    val shape = RoundedCornerShape(5.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.rule, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
fun DrawerPaperHead(label: String, link: String) {
    val colors = DaodianColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DaodianType.sectionLabel, color = colors.muted)
        Text(link, style = DaodianType.caption, color = colors.muted)
    }
}
