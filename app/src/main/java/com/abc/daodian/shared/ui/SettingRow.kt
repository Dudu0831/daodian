package com.abc.daodian.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType

/*
 * 设置页一组纸里的一行，和行首、行尾的两样小记号。设置页的框（agent/shell）和各模块的设置组共用。
 */

/** 标题 + 一句说明，右边放值或箭头。[onClick] 为空就不能点 */
@Composable
fun SettingRow(
    title: String,
    note: String? = null,
    titleColor: Color = DaodianColors.current.ink,
    noteColor: Color = DaodianColors.current.muted,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 16.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(Modifier.align(Alignment.Top).padding(top = 3.dp)) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = DaodianType.rowTitle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(note, style = DaodianType.settingNote, color = noteColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        trailing()
    }
}

/** 行首的小记号：朱砂对勾 = 好着；红色空圈 = 缺；墨灰小点 = 查不到 */
@Composable
fun Marker(ok: Boolean?) {
    val colors = DaodianColors.current
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (ok) {
            true -> CheckIcon(size = 12.dp, tint = colors.accent)
            false -> Box(Modifier.size(10.dp).border(1.3.dp, colors.red, CircleShape))
            null -> Box(Modifier.size(5.dp).background(colors.hint, CircleShape))
        }
    }
}

@Composable
fun FixLink() {
    val colors = DaodianColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("去开", style = DaodianType.caption, color = colors.accent)
        ChevronRightIcon(size = 11.dp, tint = colors.accent)
    }
}
