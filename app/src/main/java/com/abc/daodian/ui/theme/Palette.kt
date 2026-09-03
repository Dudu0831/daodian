package com.abc.daodian.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 视觉稿的色板，照抄自 daodian-ui-mockups 里的 CSS 变量。见 DESIGN.md §01 视觉方向。
 * 只在这里出现字面色值，其余地方一律通过 [DaodianColors] 引用。
 */
data class DaodianPalette(
    val paper: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val rule: Color,
    val rule2: Color,
    val green: Color,
    val greenInk: Color,
    val greenSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val red: Color,
    val redSoft: Color,
    /** 铺在 green 上的文字色 —— 浅色主题是纸色，深色主题是近黑 */
    val onGreen: Color
)

val LightPalette = DaodianPalette(
    paper = Color(0xFFF3F0E7),
    surface = Color(0xFFFBF9F3),
    surfaceAlt = Color(0xFFEBE7DA),
    ink = Color(0xFF23211B),
    ink2 = Color(0xFF4C483D),
    muted = Color(0xFF847F6E),
    rule = Color(0xFFDFD9C8),
    rule2 = Color(0xFFC9C2AE),
    green = Color(0xFF23402F),
    greenInk = Color(0xFF1B3325),
    greenSoft = Color(0xFFE3E9E0),
    amber = Color(0xFF8A6A24),
    amberSoft = Color(0xFFF2EBD8),
    red = Color(0xFF8E3A2C),
    redSoft = Color(0xFFF3E3DE),
    onGreen = Color(0xFFFBF9F3)
)

val DarkPalette = DaodianPalette(
    paper = Color(0xFF16150F),
    surface = Color(0xFF1E1C15),
    surfaceAlt = Color(0xFF272419),
    ink = Color(0xFFEFEBDE),
    ink2 = Color(0xFFCBC6B4),
    muted = Color(0xFF8F8A78),
    rule = Color(0xFF322F24),
    rule2 = Color(0xFF474334),
    green = Color(0xFF93C0A2),
    greenInk = Color(0xFFB4D6BF),
    greenSoft = Color(0xFF1C2A21),
    amber = Color(0xFFD7AC5C),
    amberSoft = Color(0xFF2A2214),
    red = Color(0xFFDB8676),
    redSoft = Color(0xFF2C1A15),
    onGreen = Color(0xFF16150F)
)

/** 「到点」全屏页专用 —— 半夜会看到它，固定深色，不跟系统主题切换。见 DESIGN.md §06 */
object AlarmPalette {
    val bg = Color(0xFF14130E)
    val bgGlow = Color(0xFF1E2A20)
    val ink = Color(0xFFF4F0E2)
    val muted = Color(0xFF8F8A78)
    val accent = Color(0xFF93C0A2)
    val accentInk = Color(0xFF16150F)
    val rule = Color(0xFF3A3628)
    val secondaryInk = Color(0xFFC9C4B2)
}
