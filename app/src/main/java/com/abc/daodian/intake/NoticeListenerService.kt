package com.abc.daodian.intake

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全 app 唯一的通知监听。所有 app 的通知都会来这里，原样打包成 [Notice] 交给 [Intake.dispatch]：
 * 勾了这个 app 的订阅者才收得到，没人勾的来了就扔。它自己一条都不存。见 DESIGN.md §2.3
 *
 * 两个坑，对策都在这里：
 *  - **正文有时被系统遮蔽**：遮蔽版照投，隔 5s / 30s / 2min 从通知栏再读同一条，真正文到了再投一次。
 *    订阅者自己定遮蔽版怎么算（记账：真正文到了把遮蔽版标「被替代」）
 *  - **实时回调会丢**：连上时、解锁时都把通知栏扫一遍兜底，订阅者也能喊它扫（[Intake.sweep]）
 *
 * 连上 / 断开报给 [Intake]（页面看连没连着、扫一遍要找到这个实例）。
 * 「通知使用权」按组件名授：这个类改名、挪包，都得再去系统设置里开一次。
 */
class NoticeListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    /** 系统绑着没有。SDK 35 没有公开的 isBound()，自己在连上 / 断开时记 */
    @Volatile
    private var connected = false

    private val onUnlock = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = sweep("unlock")
    }

    override fun onListenerConnected() {
        connected = true
        Intake.attach(this)
        // 系统的 USER_PRESENT 得开着门才进得来。注册失败绝不能抛出去把进程带崩
        runCatching { ContextCompat.registerReceiver(this, onUnlock, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED) }
        // 连上那一刻通知栏里已经挂着的也收一份
        sweep("active")
    }

    /** 系统解绑、用户收回使用权时来；框架的 onDestroy 里也会调一次 */
    override fun onListenerDisconnected() {
        connected = false
        Intake.detach(this)
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(onUnlock) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch {
            val notice = noticeOf(sbn, "posted")
            val taken = Intake.dispatch(this@NoticeListenerService, notice)
            // 有人要、正文却被遮蔽了：过一会儿再读，真正文多半就到了
            if (notice.redacted && taken.isNotEmpty()) {
                for (s in RETRIES) handler.postDelayed({ sweep("retry+${s}s") }, s * 1000L)
            }
        }
    }

    private fun sweep(how: String) {
        scope.launch { collect(how) }
    }

    /**
     * 把通知栏扫一遍，每条都交给 [Intake.dispatch]，都交完才返回。没连着返回 null ——
     * 没绑上时 getActiveNotifications() 不报错，给的是空数组，分不出「没连着」和「通知栏里没有」，所以先看连没连着。
     */
    internal suspend fun collect(how: String): Intake.Sweep? {
        if (!connected) return null
        val active = runCatching { activeNotifications }.getOrNull() ?: return null
        val routed = HashMap<String, Int>()
        active.forEach { sbn ->
            Intake.dispatch(this, noticeOf(sbn, how)).forEach { routed.merge(it.id, 1, Int::plus) }
        }
        return Intake.Sweep(active.size, routed)
    }

    private fun noticeOf(sbn: StatusBarNotification, how: String): Notice {
        val extras = sbn.notification.extras
        val dump = dump(extras)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        return Notice(
            pkg = sbn.packageName,
            key = sbn.key,
            postTime = sbn.postTime,
            at = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = text,
            extra = extraOf(dump, text),
            extras = dump.toString(),
            redacted = text?.contains(Intake.REDACTED) == true,
            how = how
        )
    }

    private companion object {
        /** 遮蔽之后重扫的时刻（秒）。实测 5 秒那次就拿到真正文了 */
        val RETRIES = listOf(5, 30, 120)

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

        /** bigText、subText、消息样式里和正文不重复的部分 */
        fun extraOf(extras: JSONObject, text: String?): String? = listOfNotNull(
            extras.optString("android.bigText").ifBlank { null }?.takeIf { it != text },
            extras.optString("android.subText").ifBlank { null },
            extras.optJSONArray("_messages")?.let { a -> (0 until a.length()).joinToString(" / ") { a.optString(it) } }
        ).joinToString(" · ").ifBlank { null }
    }
}
