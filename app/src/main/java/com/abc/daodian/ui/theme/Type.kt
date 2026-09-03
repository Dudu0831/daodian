package com.abc.daodian.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 字体角色。视觉稿用的是 Newsreader / Noto Serif SC / IBM Plex Mono ——
 * 这里换成系统族（Serif / Default / Monospace）而不是打包 Google Fonts webfont。
 *
 * 原因是包体：Noto Serif SC 覆盖全部 CJK 字形，完整字重通常有几 MB 到十几 MB，
 * 而 M2 引入 openai-java 已经把包体从 2MB 顶到 35MB（见 DESIGN.md 决策 3.1）。
 * 再叠一份重字体，对一个侧载自用 app 的性价比存疑，所以先用系统族凑合。
 * 荣耀 MagicOS 的系统衬体 fallback 链常带中文宋体，观感不会太离谱；
 * 斜体标题（「下午好——」）在系统族上是合成斜体，效果跟视觉稿里浏览器对
 * 非斜体中文字形做的合成倾斜是同一回事，不算凑合出来的额外妥协。
 * 如果你想要像素级还原，告诉我，我把 Noto Serif SC 的具体字重打进 res/font/。
 */
object DaodianFonts {
    val serif = FontFamily.Serif
    val body = FontFamily.Default
    val mono = FontFamily.Monospace
}

object DaodianType {
    val wordmark = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp
    )

    val greetingItalic = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        fontSize = 27.sp,
        lineHeight = 35.sp
    )

    val greetingBold = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 29.sp
    )

    val cardTitle = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    )

    val alarmTitle = TextStyle(
        fontFamily = DaodianFonts.serif,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 50.sp
    )

    val monoLabel = TextStyle(
        fontFamily = DaodianFonts.mono,
        fontSize = 10.5.sp,
        letterSpacing = 0.14.em
    )

    val monoSmall = TextStyle(
        fontFamily = DaodianFonts.mono,
        fontSize = 10.sp,
        letterSpacing = 0.1.em
    )

    val body = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 15.sp,
        lineHeight = 23.sp
    )

    val bodySmall = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    )

    val caption = TextStyle(
        fontFamily = DaodianFonts.body,
        fontSize = 12.5.sp
    )
}
