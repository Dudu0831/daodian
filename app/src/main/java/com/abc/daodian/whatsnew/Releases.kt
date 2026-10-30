package com.abc.daodian.whatsnew

/**
 * 弹窗的一页：上面一段动图，下面 [title] 和一句 [line]。
 * [media] 是 `assets/` 里的动态 WebP（three.js 简笔画录出来的），null 或文件不在就只画那块底色。
 */
class Page(val title: String, val line: String, val media: String? = null)

/** 一版的新功能。[versionCode] 对的是 `app/build.gradle.kts` 里的那个数 */
class Release(val versionCode: Int, val versionName: String, val pages: List<Page>)

/**
 * 每一版写了哪几页。发版前在这里加一条、把动图放进 `assets/whatsnew/<versionCode>/`；不写就不弹。
 * 只弹当前这一版的（全新装的也看这一版），跳了几版升上来的不补看前面的。
 * 包里只留最近一两版：更老的连 assets 里的图一起删，不然包越来越大。
 */
object Releases {

    val ALL: List<Release> = listOf(
        Release(
            versionCode = 13,
            versionName = "0.2.0",
            pages = listOf(
                Page(
                    title = "通知管家",
                    line = "在对话里面说一句，比如「帮我监听菜鸟和短信的快递」。以前勾的要重新说。",
                    media = "whatsnew/13/notice.webp"
                )
            )
        )
    )

    fun of(versionCode: Int): Release? = ALL.firstOrNull { it.versionCode == versionCode }?.takeIf { it.pages.isNotEmpty() }
}
