package com.abc.daodian.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 字体角色。视觉稿用的是 Noto Serif SC（标题、数字、序号）+ Noto Sans SC（正文）+ 等宽（「依据」行），
 * 这里换成系统族（Serif / Default / Monospace）而不是打包 Google Fonts webfont。
 *
 * 原因是包体：Noto Serif SC 覆盖全部 CJK 字形，完整字重通常有几 MB 到十几 MB，
 * 而 M2 引入 openai-java 已经把包体从 2MB 顶到 35MB（见 DESIGN.md 决策 3.1）。
 * 荣耀 MagicOS 的系统衬体 fallback 链常带中文宋体，「墨宋」的观感基本立得住。
 * 这是已知的、故意留下的差距 —— 想要像素级还原就往 res/font/ 里塞真实字重。
 *
 * 注意：系统衬体通常只有 400/700 两档，稿子里的 300 / 500 / 600 会被合成到最近的一档，
 * 所以「粗细拉开层次」这件事这里靠字号和字距扛，不指望字重。
 */
object DaodianFonts {
    val serif = FontFamily.Serif
    val body = FontFamily.Default
    val mono = FontFamily.Monospace
}

object DaodianType {

    // ---- 标识 ----

    /** 顶栏「到点」 */
    val wordmark = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = 0.16.em
    )

    // ---- 空状态的招呼语 ----

    /** 「晚上好——」，轻的那半句 */
    val greetingSoft = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Light,
        fontSize = 31.sp,
        lineHeight = 46.sp
    )

    /** 「有什么要记着的？」 */
    val greeting = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Medium,
        fontSize = 31.sp,
        lineHeight = 46.sp
    )

    /** 例句前的「一二三四」 */
    val ordinal = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontSize = 13.sp
    )

    // ---- 小标签（一律大字距，靠疏密而不是靠粗细拉层次）----

    /** 「这样说就行」这类分节标签 */
    val sectionLabel = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 11.sp,
        letterSpacing = 0.22.em
    )

    /** 助手气泡上方的「到点」 */
    val speakerTag = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 10.5.sp,
        letterSpacing = 0.22.em
    )

    /** 卡片里的「已记下」 */
    val stampLabel = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 10.sp,
        letterSpacing = 0.2.em
    )

    // ---- 正文 ----

    /** 气泡、例句、输入框 */
    val body = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 14.5.sp,
        lineHeight = 24.sp
    )

    /** 助手的成段文字，行距更松 */
    val prose = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 15.sp,
        lineHeight = 28.sp
    )

    val bodySmall = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 13.5.sp,
        lineHeight = 21.sp
    )

    val caption = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 12.5.sp
    )

    /** 重复徽标里的「每月 2 号」 */
    val badge = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 11.5.sp
    )

    /** 「正在推算时间——」 */
    val thinkingNote = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Light,
        fontSize = 12.5.sp,
        letterSpacing = 0.1.em
    )

    /** 卡片标题 */
    val cardTitle = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    )

    /** 收起态卡片、列表行的标题 */
    val rowTitle = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    )

    /** 页头标题 */
    val screenTitle = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.06.em
    )

    /** 「依据」那行 —— 模型的推算过程，等宽，不要删（见视觉稿组件展板的批注） */
    val basis = TextStyle(
        fontFamily = DaodianFonts.mono,
        fontSize = 10.5.sp,
        lineHeight = 17.sp
    )

    // ---- 到点全屏页 ----

    /** 「九月二日 · 周三」 */
    val alarmDate = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Light,
        fontSize = 15.sp,
        letterSpacing = 0.34.em
    )

    /** 大时钟 */
    val alarmClock = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Light,
        fontSize = 62.sp,
        lineHeight = 78.sp,
        letterSpacing = 0.06.em
    )

    val alarmTag = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 10.5.sp,
        letterSpacing = 0.26.em
    )

    val alarmTitle = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 58.sp,
        letterSpacing = 0.02.em
    )

    /** 大按钮上的字 */
    val button = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 16.sp,
        letterSpacing = 0.1.em
    )
}
