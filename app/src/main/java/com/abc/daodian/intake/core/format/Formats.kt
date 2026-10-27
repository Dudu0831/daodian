package com.abc.daodian.intake.core.format

/** 包名 → 整理格式（Registry）。认一种新格式 = 写一个 [NoticeFormat]，在这里登记它的包名 */
object Formats {

    /** 聊天软件，和 `AppCatalog` 里那张单子一样（核心不碰 Android，抄一份） */
    private val BY_PKG: Map<String, NoticeFormat> = mapOf(
        "com.tencent.mm" to ChatFormat,            // 微信
        "com.tencent.mobileqq" to ChatFormat,      // QQ
        "com.tencent.tim" to ChatFormat,           // TIM
        "com.alibaba.android.rimet" to ChatFormat, // 钉钉
        "com.ss.android.lark" to ChatFormat        // 飞书
    )

    fun of(pkg: String): NoticeFormat = BY_PKG[pkg] ?: PlainFormat
}
