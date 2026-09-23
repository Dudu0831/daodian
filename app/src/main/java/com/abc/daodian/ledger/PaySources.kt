package com.abc.daodian.ledger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * 听哪几家的通知，以及跟「通知使用权」打交道的几件事。
 *
 * 加一家：在 [ALL] 里加一行（包名 + 人话名字）就行 —— 采集、整理、界面都从这里取。
 * 各家通知给得出什么，见 LEDGER_PLAN.md §2.1。
 */
object PaySources {

    data class Source(val pkg: String, val name: String)

    val ALL = listOf(
        Source("com.eg.android.AlipayGphone", "支付宝"),
        Source("cmb.pb", "招商银行"),
        Source("com.cmbchina.ccd.pluto.cmbActivity", "掌上生活"),
        Source("com.chinamworld.main", "建设银行"),
    )

    val PACKAGES: Set<String> = ALL.mapTo(HashSet()) { it.pkg }

    fun nameOf(pkg: String): String = ALL.firstOrNull { it.pkg == pkg }?.name ?: pkg

    /** 界面上写「支付宝、招行…」时用 */
    val names: String get() = ALL.joinToString("、") { it.name }

    /**
     * 系统遮蔽敏感通知时填进正文的那句话。直接问 framework 要（隐藏资源
     * `android:string/redacted_notification_message`），跟着系统语言走；问不到才退回中文原句。
     * 为什么会被遮蔽见 LEDGER_PLAN.md §2.2。
     */
    val REDACTED: String by lazy {
        runCatching {
            val res = Resources.getSystem()
            val id = res.getIdentifier("redacted_notification_message", "string", "android")
            id.takeIf { it != 0 }?.let { res.getString(it) }
        }.getOrNull() ?: "敏感数据已隐藏"
    }

    /** app 内广播：让采集器把当前通知栏再扫一遍 */
    const val ACTION_SWEEP = "com.abc.daodian.PAY_SWEEP"

    fun granted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** 直达本 app 的「通知使用权」开关 */
    fun grantIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, PaySampler::class.java).flattenToString()
            )

    /** 让采集器把通知栏再扫一遍（遮蔽的正文过一会儿再读往往是全的）。整理之前先喊一声 */
    fun sweep(context: Context) {
        context.applicationContext.sendBroadcast(Intent(ACTION_SWEEP).setPackage(context.packageName))
    }

    /** 荣耀杀掉监听后系统不一定自己绑回来；app 每次冷启动催一下 */
    fun rebind(context: Context) {
        if (granted(context)) runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, PaySampler::class.java))
        }
    }
}
