package com.abc.daodian.shared.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 手机上装了哪些 app：记账「听哪些 app」、派活「听哪个 app」两页列出来给你勾，抓到的通知是哪家也从这里取名字。
 *
 * 只列桌面上有图标的（manifest 里 `<queries>` 声明了 MAIN / LAUNCHER，不用 QUERY_ALL_PACKAGES）：
 * 银行、支付、短信都有图标；没图标的多是系统组件，不会发付款通知。见 DESIGN.md §10.2
 */
object AppCatalog {

    data class App(val pkg: String, val label: String)

    private val labels = ConcurrentHashMap<String, String>()

    /** 桌面上有图标的 app，按名字排（中文按拼音），不含自己。系统不让看时是空的 */
    fun launchable(context: Context): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching { pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0)) }
            .getOrDefault(emptyList())
        val collator = Collator.getInstance(Locale.CHINA)
        return found.asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info -> App(info.packageName, pm.getApplicationLabel(info).toString().also { labels[info.packageName] = it }) }
            .sortedWith(compareBy(collator) { it.label })
            .toList()
    }

    /** 包名 → 人话名字。卸载了、系统不让看的，退回包名 */
    fun label(context: Context, pkg: String): String = labels[pkg] ?: runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrNull()?.also { labels[pkg] = it } ?: pkg

    /** 还装着没有（勾过又卸载了的，那一页上要写一句） */
    fun installed(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    }.isSuccess

    /** app 图标，画成 [px] 见方。拿不到是 null */
    fun icon(context: Context, pkg: String, px: Int): Bitmap? = runCatching {
        context.packageManager.getApplicationIcon(pkg).toBitmap(px, px)
    }.getOrNull()
}
