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
            versionCode = 14,
            versionName = "0.2.1",
            pages = listOf(
                Page(
                    title = "按住说话",
                    line = "按住麦克风说，松手就发；拖到右边，看着字再发。声音在手机里认，不上传。",
                    media = "whatsnew/14/voice.webp"
                ),
                Page(
                    title = "说一句就改",
                    line = "「喝水那个改成 9 点」「删了明天的提醒」，不用自己翻列表。",
                    media = "whatsnew/14/edit.webp"
                ),
                Page(
                    title = "让它画给你看",
                    line = "「把这周的开销画成图」，图就画在对话里，还能点。",
                    media = "whatsnew/14/widget.webp"
                )
            )
        ),
        Release(
            versionCode = 15,
            versionName = "0.2.2",
            pages = listOf(
                Page(
                    title = "想多久，你定",
                    line = "点顶上的印，往下滑。印泥越浓，想得越久，答得也越慢。",
                    media = "whatsnew/15/effort.webp"
                ),
                Page(
                    title = "日子还远，先记下",
                    line = "「12 月 23 号去旧金山，提前一周提醒我」。正日子之前，那天晚上先轻轻提一下。",
                    media = "whatsnew/15/remind.webp"
                ),
                Page(
                    title = "要带的，挂在提醒底下",
                    line = "「照去年那张，列个行李清单」。它先翻以前的清单再列，到点告诉你还剩几样。",
                    media = "whatsnew/15/list.webp"
                ),
                Page(
                    title = "钉到桌面上",
                    line = "「做张去旧金山的倒数卡片，带上清单」。看着顺眼就点「放到桌面」，在桌面上直接勾。",
                    media = "whatsnew/15/card.webp"
                )
            )
        )
    )

    fun of(versionCode: Int): Release? = ALL.firstOrNull { it.versionCode == versionCode }?.takeIf { it.pages.isNotEmpty() }
}
