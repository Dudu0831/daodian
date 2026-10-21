package com.abc.daodian.reminder.delivery

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import com.abc.daodian.shared.format.DiagnosticText
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 到点那一刻手机是什么样子，一次一行，写在 `files/fire_probe.txt`，由「导出诊断」带出来。
 *
 * 导出时的状态说明不了到点时的事：「全屏页没弹」多半是那一刻亮着屏在用（系统只给横幅），
 * 「只响不震」要看那一刻的响铃模式、渠道震不震。fire_log 只记了响没响、准不准，这里补上那一刻的样子
 * 和全屏页到底起没起来。不写标题，只写 #id。只留最近 [KEEP] 行。
 */
object FireProbe {

    private const val KEEP = 80
    private const val FILE = "fire_probe.txt"

    /** 这个进程里每条提醒最近一次发通知的时刻：全屏页起来时算隔了多久 */
    private val posted = ConcurrentHashMap<Long, Long>()

    /** 发完通知（或者没发成）记一行。[fullScreen]：通知上挂没挂全屏 intent；null 是这一档本来就不挂 */
    fun fired(context: Context, reminderId: Long, kind: String, channelId: String, fullScreen: Boolean?, sent: Boolean) {
        val now = System.currentTimeMillis()
        if (sent) posted[reminderId] = now
        val fsi = when (fullScreen) {
            true -> " · 挂了全屏"
            false -> " · 没挂全屏（没许）"
            null -> ""
        }
        append(context, "${DiagnosticText.time(now)} #$reminderId $kind · ${if (sent) "发出" else "没发出"}$fsi · ${state(context, channelId)}")
    }

    /** 全屏页起来了 */
    fun shown(context: Context, reminderId: Long) {
        val now = System.currentTimeMillis()
        val gap = posted[reminderId]?.let { " · 发出后 ${now - it}ms" } ?: " · 发通知的不是这个进程"
        append(context, "${DiagnosticText.time(now)} #$reminderId 全屏页起来了$gap · ${screen(context)}")
    }

    /** 新的在前 */
    fun recent(context: Context, n: Int = 40): List<String> =
        runCatching { File(context.filesDir, FILE).readLines() }.getOrDefault(emptyList()).takeLast(n).reversed()

    private fun screen(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(KeyguardManager::class.java)
        return "${if (pm.isInteractive) "亮屏" else "灭屏"}${if (km.isKeyguardLocked) "锁着" else "没锁"}"
    }

    private fun state(context: Context, channelId: String): String = runCatching {
        val pm = context.getSystemService(PowerManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "响铃"
            AudioManager.RINGER_MODE_VIBRATE -> "振动"
            AudioManager.RINGER_MODE_SILENT -> "静音"
            else -> "${audio.ringerMode}"
        }
        fun vol(stream: Int) = "${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}"
        val dnd = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "关"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "只放优先"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "只放闹钟"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "全静"
            else -> "${nm.currentInterruptionFilter}"
        }
        val ch = nm.getNotificationChannel(channelId)
        val channel = if (ch == null) "渠道没了" else "渠道 ${ch.importance}${if (ch.shouldVibrate()) "震" else "不震"}${if (ch.sound == null) "无声" else ""}"
        "${screen(context)} · $ringer · 闹钟 ${vol(AudioManager.STREAM_ALARM)} 通知 ${vol(AudioManager.STREAM_NOTIFICATION)} · " +
            "免打扰 $dnd · $channel · 通知${if (nm.areNotificationsEnabled()) "开" else "关"}" +
            (if (pm.isPowerSaveMode) " · 省电模式" else "") + (if (pm.isDeviceIdleMode) " · 深度休眠" else "")
    }.getOrElse { "读状态出错 ${it.javaClass.simpleName}" }

    @Synchronized
    private fun append(context: Context, line: String) {
        runCatching {
            val f = File(context.filesDir, FILE)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + line
            f.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
        }
    }
}
