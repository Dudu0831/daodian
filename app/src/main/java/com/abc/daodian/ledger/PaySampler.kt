package com.abc.daodian.ledger

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记账调研用的通知采样器：支付宝 / 招商银行 / 掌上生活三家的通知，**原样全存**，不过滤、不解析。
 * 目的是先看一周真实通知长什么样，再定解析和去重规则。记账功能本身还没开始做。
 *
 * 存成 JSON Lines，一行一条，不进 Room —— 这是临时调研，不碰 data/ 的迁移。读法：
 *
 *     adb shell run-as com.abc.daodian.debug cat files/pay_samples.jsonl
 *
 * 除了通知本身，监听连上 / 断开也各记一行（ev = connected / disconnected），
 * 荣耀杀后台时能从断档看出来哪段时间没在录。
 */
class PaySampler : NotificationListenerService() {

    override fun onListenerConnected() {
        PaySamples.append(this, JSONObject().put("ev", "connected").put("at", now()))
        // 连上那一刻通知栏里已经挂着的也收一份，免得错过授权之前的那几条
        runCatching { activeNotifications }.getOrNull()
            ?.filter { it.packageName in PaySamples.PACKAGES }
            ?.forEach { PaySamples.append(this, describe(it, "active")) }
    }

    override fun onListenerDisconnected() {
        PaySamples.append(this, JSONObject().put("ev", "disconnected").put("at", now()))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PaySamples.PACKAGES) return
        PaySamples.append(this, describe(sbn, "posted"))
    }

    private fun describe(sbn: StatusBarNotification, ev: String): JSONObject {
        val n = sbn.notification
        return JSONObject()
            .put("ev", ev)
            .put("at", now())
            .put("pkg", sbn.packageName)
            .put("key", sbn.key)
            .put("id", sbn.id)
            .put("tag", sbn.tag)
            .put("postTime", fmt(sbn.postTime))
            .put("when", fmt(n.`when`))
            .put("channel", n.channelId)
            .put("category", n.category)
            .put("group", n.group)
            .put("ongoing", sbn.isOngoing)
            .put("flags", n.flags)
            .put("ticker", n.tickerText?.toString())
            .put("extras", dump(n.extras))
    }

    /** extras 里能变成文字的全收；位图、PendingIntent 这类只记类名 */
    private fun dump(extras: Bundle?): JSONObject {
        val out = JSONObject()
        if (extras == null) return out
        for (k in extras.keySet()) {
            @Suppress("DEPRECATION")
            val v = runCatching { extras.get(k) }.getOrNull() ?: continue
            out.put(k, when (v) {
                is CharSequence, is Number, is Boolean -> v.toString()
                is Array<*> -> JSONArray(v.map { (it as? CharSequence)?.toString() ?: it?.javaClass?.simpleName })
                is Bundle -> dump(v)
                else -> "<${v.javaClass.simpleName}>"
            })
        }
        // 消息样式（MessagingStyle）的正文藏在 android.messages 的 Bundle 数组里，单独展开
        Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        ).takeIf { it.isNotEmpty() }?.let { msgs ->
            out.put("_messages", JSONArray(msgs.map { "${it.senderPerson?.name ?: ""}: ${it.text}" }))
        }
        return out
    }

    private fun now() = fmt(System.currentTimeMillis())

    private fun fmt(ms: Long) = if (ms <= 0) null else STAMP.format(Instant.ofEpochMilli(ms))

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

object PaySamples {

    /** 用户选定的三家：支付宝、招商银行、掌上生活（招行信用卡） */
    val PACKAGES = setOf(
        "com.eg.android.AlipayGphone",
        "cmb.pb",
        "com.cmbchina.ccd.pluto.cmbActivity",
    )

    private const val FILE = "pay_samples.jsonl"

    /** 三家一周撑死几百条，这个上限只是防意外（某家疯狂刷新进度通知） */
    private const val MAX_BYTES = 8L * 1024 * 1024

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    @Synchronized
    fun append(context: Context, line: JSONObject) {
        runCatching {
            val f = file(context)
            if (f.length() > MAX_BYTES) return
            f.appendText(line.toString() + "\n")
        }
    }

    /** 采到了几条通知（不算连上 / 断开那种事件行） */
    fun count(context: Context): Int = runCatching {
        file(context).useLines { lines -> lines.count { !it.contains("\"ev\":\"connected\"") && !it.contains("\"ev\":\"disconnected\"") } }
    }.getOrDefault(0)

    fun granted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** 直达本 app 的「通知使用权」开关 */
    fun grantIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, PaySampler::class.java).flattenToString()
            )

    /** 荣耀杀掉监听后系统不一定自己绑回来；app 每次冷启动催一下 */
    fun rebind(context: Context) {
        if (granted(context)) runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, PaySampler::class.java))
        }
    }
}
