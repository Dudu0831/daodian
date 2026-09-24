package com.abc.daodian.ledger.capture

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.abc.daodian.ledger.data.LedgerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 支付通知采集器：[PaySources] 里那几家的通知**原样**存进 `raw_notification`，不解析 ——
 * 读懂是整理 agent 的事（DESIGN.md §10.1）。
 *
 * 类名还叫 PaySampler（调研阶段的名字）：「通知使用权」是按组件名授的，改名就得重新去系统设置里开一次。
 *
 * 两个坑，对策都在这里：
 *  - **正文有时被系统遮蔽**（DESIGN.md §10.2）：遮蔽版照存，隔 5s / 30s / 2min 从通知栏再读同一条，
 *    真正文到了由 [LedgerStore.ingest] 把遮蔽版标成 SUPERSEDED
 *  - **实时回调会丢**（§10.2）：连上时、解锁时、整理之前（[PaySources.sweep]）都把通知栏扫一遍兜底，
 *    抓取页上还能手动扫（[PaySources.sweepNow]）；同一条通知同一段正文靠指纹只存一次
 *
 * 连上 / 断开报给 [PaySources]（抓取页看连没连着、手动扫要找到这个实例）。
 */
class PaySampler : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private val onSweep = sweeper("organize")
    private val onUnlock = sweeper("unlock")

    private fun sweeper(how: String) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = sweep(how)
    }

    override fun onListenerConnected() {
        PaySources.attach(this)
        // 自家广播关着门收，系统的 USER_PRESENT 得开着门才进得来。注册失败绝不能抛出去把进程带崩
        runCatching {
            ContextCompat.registerReceiver(this, onSweep, IntentFilter(PaySources.ACTION_SWEEP), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, onUnlock, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        }
        // 连上那一刻通知栏里已经挂着的也收一份
        sweep("active")
    }

    /** 系统解绑、用户收回使用权时来；框架的 onDestroy 里也会调一次 */
    override fun onListenerDisconnected() {
        PaySources.detach(this)
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(onSweep) }
        runCatching { unregisterReceiver(onUnlock) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PaySources.PACKAGES) return
        save(sbn, "posted")
        val text = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (text?.contains(PaySources.REDACTED) == true) {
            for (s in RETRIES) handler.postDelayed({ sweep("retry+${s}s") }, s * 1000L)
        }
    }

    private fun sweep(how: String) {
        scope.launch { collect(how) }
    }

    /**
     * 把通知栏里那几家的通知收一遍，存完才返回。返回挂着几条；没连着返回 null ——
     * 没绑上时 getActiveNotifications() 不报错，给的是空数组，分不出「没连着」和「通知栏里没有」，所以先问 isBound。
     */
    internal suspend fun collect(how: String): Int? {
        if (!isBound()) return null
        val mine = runCatching { activeNotifications }.getOrNull()
            ?.filter { it.packageName in PaySources.PACKAGES }
            ?: return null
        mine.forEach { store(it, how) }
        return mine.size
    }

    private fun save(sbn: StatusBarNotification, how: String) {
        scope.launch { store(sbn, how) }
    }

    /** 存一条。新存进去了返回 true，指纹重复（早就存过）返回 false */
    private suspend fun store(sbn: StatusBarNotification, how: String): Boolean = runCatching {
        val extras = sbn.notification.extras
        val dump = dump(extras)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        LedgerStore.get(this).ingest(
            pkg = sbn.packageName,
            key = sbn.key,
            postTime = sbn.postTime,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = text,
            extra = extraOf(dump, text),
            extras = dump.toString(),
            how = how
        )
    }.getOrDefault(false)

    companion object {
        /** 遮蔽之后重扫的时刻（秒）。实测 5 秒那次就拿到真正文了 */
        private val RETRIES = listOf(5, 30, 120)

        /** extras 里能变成文字的全收；位图、PendingIntent 这类只记类名 */
        fun dump(extras: Bundle?): JSONObject {
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
            runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ).takeIf { it.isNotEmpty() }?.let { msgs ->
                    out.put("_messages", JSONArray(msgs.map { "${it.senderPerson?.name ?: ""}: ${it.text}" }))
                }
            }
            return out
        }

        /** bigText、subText、消息样式里和正文不重复的部分 —— 有的银行把明细写在展开后的大字里 */
        fun extraOf(extras: JSONObject, text: String?): String? = listOfNotNull(
            extras.optString("android.bigText").ifBlank { null }?.takeIf { it != text },
            extras.optString("android.subText").ifBlank { null },
            extras.optJSONArray("_messages")?.let { a -> (0 until a.length()).joinToString(" / ") { a.optString(it) } }
        ).joinToString(" · ").ifBlank { null }
    }
}
