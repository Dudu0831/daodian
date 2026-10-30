package com.abc.daodian.whatsnew

import android.content.Context
import com.abc.daodian.BuildConfig

/**
 * 弹没弹过：prefs 里记看过的最后一版。点了叉、按了返回才算看过 —— 弹着的时候杀掉进程，下次打开还弹。
 * prefs 名字 `whatsnew` 存在手机上，不改（§2.2）
 */
object WhatsNew {

    private const val PREFS = "whatsnew"
    private const val KEY_SEEN = "seen"

    /** 这一版要弹的那几页。看过了、或者这一版没写（[Releases]），是 null */
    fun pending(context: Context): Release? =
        if (seen(context) >= BuildConfig.VERSION_CODE) null else Releases.of(BuildConfig.VERSION_CODE)

    fun dismiss(context: Context) {
        prefs(context).edit().putInt(KEY_SEEN, BuildConfig.VERSION_CODE).apply()
    }

    /** 看过的最后一版的 versionCode，一次没看过是 0 */
    fun seen(context: Context): Int = prefs(context).getInt(KEY_SEEN, 0)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
