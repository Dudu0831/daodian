package com.abc.daodian.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「墨宋」色板 —— 照抄自视觉稿（Claude Design 画布 c7073888）。见 DESIGN.md §8.1 视觉语言。
 *
 * 三条规矩，改色之前先读：
 * 1. 宣纸底 + 墨色主体。实心按钮、用户气泡一律用 [solid]（浅色下就是墨黑），不是彩色。
 * 2. 朱砂 [accent] 只做印章式点缀 —— 圆点、对勾、「已记下」这类小标记，绝不铺成大色块。
 * 3. 只在这个文件里出现字面色值，其余地方一律通过 [DaodianColors] 引用。
 */
data class DaodianPalette(
    /** 页面底色，宣纸 */
    val paper: Color,
    /** 卡片 / 输入框底色，比纸再亮一点 */
    val surface: Color,
    /** 次级块面（组件展板、骨架屏底） */
    val surfaceAlt: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    /** 占位符这类最轻的字 */
    val hint: Color,
    /** 分隔线 */
    val rule: Color,
    /** 描边（比 rule 重，用在按钮边框） */
    val rule2: Color,
    /** 卡片内部的细分隔线，比 rule 还轻 */
    val ruleSoft: Color,
    /** 朱砂，印章式点缀 */
    val accent: Color,
    /** 实心块（按钮、用户气泡）—— 浅色是墨黑，深色是米白 */
    val solid: Color,
    /** 铺在 [solid] 上的字色 */
    val onSolid: Color,
    /** 解析中的骨架条 */
    val skeleton: Color,
    /** 出错 / 迟到这类需要被看见的告警字色 */
    val red: Color,
    val amber: Color
)

val LightPalette = DaodianPalette(
    paper = Color(0xFFF2EFE6),
    surface = Color(0xFFF8F6EF),
    surfaceAlt = Color(0xFFEAE6DA),
    ink = Color(0xFF1C1A17),
    ink2 = Color(0xFF3A372F),
    muted = Color(0xFF8A8578),
    hint = Color(0xFFA9A395),
    rule = Color(0xFFDFDACC),
    rule2 = Color(0xFFC9C3B3),
    ruleSoft = Color(0xFFE5E0D2),
    accent = Color(0xFF9E3B2E),
    solid = Color(0xFF1C1A17),
    onSolid = Color(0xFFF2EFE6),
    skeleton = Color(0xFFE2DDCF),
    red = Color(0xFFB0533F),
    amber = Color(0xFF8A6A24)
)

val DarkPalette = DaodianPalette(
    paper = Color(0xFF14130F),
    surface = Color(0xFF1C1A15),
    surfaceAlt = Color(0xFF22201A),
    ink = Color(0xFFEDEAE0),
    ink2 = Color(0xFFC3BEB0),
    muted = Color(0xFF7C7869),
    hint = Color(0xFF6E6A5E),
    rule = Color(0xFF2E2B23),
    rule2 = Color(0xFF38352C),
    ruleSoft = Color(0xFF2A2720),
    accent = Color(0xFFC4604F),
    solid = Color(0xFFE6E2D6),
    onSolid = Color(0xFF14130F),
    skeleton = Color(0xFF272419),
    red = Color(0xFFD6786A),
    amber = Color(0xFFD7AC5C)
)
