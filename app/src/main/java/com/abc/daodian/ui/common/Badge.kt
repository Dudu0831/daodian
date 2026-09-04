package com.abc.daodian.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abc.daodian.ui.theme.DaodianColors
import com.abc.daodian.ui.theme.DaodianType

/**
 * 「每月 2 号」这类重复徽标 —— 写人话，不写 RRULE，只在真有重复规则时出现。
 * 细描边而不是色块：朱砂在这套视觉里只做印章式点缀，铺成底色就俗了。见视觉稿组件展板的批注。
 */
@Composable
fun RepeatBadge(text: String, modifier: Modifier = Modifier) {
    val colors = DaodianColors.current
    OutlineBadge(text, modifier) { RepeatIcon(tint = colors.ink2) }
}

/** 描边小标签。徽标一律描边不填色，理由同上 */
@Composable
fun OutlineBadge(
    text: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null
) {
    val colors = DaodianColors.current
    Row(
        modifier
            .border(1.dp, colors.rule, RoundedCornerShape(3.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        leading?.invoke()
        Text(text, style = DaodianType.badge, color = colors.ink2)
    }
}
