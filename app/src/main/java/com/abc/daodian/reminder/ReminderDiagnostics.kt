package com.abc.daodian.reminder

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.abc.daodian.reminder.delivery.FireProbe
import com.abc.daodian.reminder.delivery.Notifier
import com.abc.daodian.reminder.data.ReminderDatabase
import com.abc.daodian.shared.data.rows
import com.abc.daodian.shared.format.DiagnosticText.time

/**
 * 「导出诊断」里提醒那一段：出不出得了声、震不震、弹不弹全屏，排了几条、下一次什么时候、最近响得准不准、
 * 到点那一刻手机什么样。不写标题、不写原话
 */
internal object ReminderDiagnostics {

    suspend fun build(context: Context): String = buildString {
        val db = ReminderDatabase.get(context).openHelper.readableDatabase
        val now = System.currentTimeMillis()

        sound(context)
        appendLine()
        vibration(context)
        appendLine()
        fullScreen(context)
        appendLine()
        systemSettings(context)
        appendLine()

        appendLine("按状态：" + db.rows("SELECT status, COUNT(*) FROM reminders GROUP BY status")
            .joinToString("、") { "${it[0]} ${it[1]}" }.ifEmpty { "一条没有" })

        val due = db.rows(
            "SELECT id, nextTriggerAt, rrule IS NOT NULL, dueDay IS NOT NULL, gentle FROM reminders " +
                "WHERE status = 'SCHEDULED' ORDER BY nextTriggerAt LIMIT 30"
        )
        appendLine("排着的（最近 30 条，#id · 下次 · 重复 / 当天事项 / 轻提醒；过点没响的打 !）：")
        due.forEach { (id, next, repeat, day, gentle) ->
            val at = next?.toLongOrNull()
            val late = at != null && at < now - 60_000
            appendLine("  ${if (late) "!" else " "} #$id · ${time(at)}" +
                (if (repeat == "1") " · 重复" else "") + (if (day == "1") " · 当天" else "") +
                (if (gentle == "1" && day != "1") " · 轻提醒" else ""))
        }

        appendLine("最近响的（30 次，#提醒 · 该响 · 实际 · 晚了多少 · 来路）：")
        db.rows("SELECT reminderId, scheduledAt, firedAt, source FROM fire_log ORDER BY id DESC LIMIT 30").forEach { (id, sch, fired, src) ->
            val drift = (fired?.toLongOrNull() ?: 0) - (sch?.toLongOrNull() ?: 0)
            appendLine("  #$id · ${time(sch?.toLongOrNull())} · ${time(fired?.toLongOrNull())} · ${drift}ms · $src")
        }

        // 这几行最要紧：「全屏没弹」先看那一刻是不是亮屏没锁（系统只给横幅）、有没有「全屏页起来了」那一行；
        // 「只响不震」看那一刻的响铃模式、渠道震不震
        val probes = FireProbe.recent(context)
        appendLine("到点那一刻（${probes.size} 行，新的在前；没有「全屏页起来了」那一行就是全屏页没起来）：")
        probes.forEach { appendLine("  $it") }
    }

    /**
     * 到点出不出得了声。响铃那一档的声音全靠渠道（Notifier.ensureChannel），系统这几处任何一处拦了就只震或全哑：
     * 响铃模式（一加的三段键）、闹钟 / 通知音量、免打扰放不放行闹钟、ROM 有没有把渠道的声音和震动改掉。
     */
    private fun StringBuilder.sound(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)

        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "响铃"
            AudioManager.RINGER_MODE_VIBRATE -> "振动"
            AudioManager.RINGER_MODE_SILENT -> "静音"
            else -> "${audio.ringerMode}"
        }
        fun volume(stream: Int) = "${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}" +
            if (audio.isStreamMute(stream)) "（静音）" else ""
        appendLine("响铃模式 $ringer · 音量 闹钟 ${volume(AudioManager.STREAM_ALARM)}、通知 ${volume(AudioManager.STREAM_NOTIFICATION)}、" +
            "铃声 ${volume(AudioManager.STREAM_RING)}、媒体 ${volume(AudioManager.STREAM_MUSIC)}")
        appendLine("声音此刻走 ${audio.mode.let { when (it) {
            AudioManager.MODE_NORMAL -> "正常"
            AudioManager.MODE_IN_CALL -> "通话中"
            AudioManager.MODE_IN_COMMUNICATION -> "语音 / 视频通话中"
            AudioManager.MODE_RINGTONE -> "来电响铃"
            else -> "mode $it"
        } }} · 外放以外的输出 ${runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.filterNot { it in BUILT_IN }
                .joinToString("、") { OUTPUTS[it] ?: "type $it" }.ifEmpty { "没有" }
        }.getOrDefault("读不到")}（耳机、蓝牙连着时闹钟可能只从那边出）")

        val dnd = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "关"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "开（只放行优先的）"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "开（只放行闹钟）"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "开（全部静音）"
            else -> "读不到（${nm.currentInterruptionFilter}）"
        }
        appendLine("免打扰 $dnd · zen_mode ${runCatching { Settings.Global.getInt(context.contentResolver, "zen_mode") }.getOrNull() ?: "读不到"}")
        // 免打扰的规矩：开着「只放优先」时放不放闹钟、屏蔽了哪些视觉效果（全屏、横幅…）。读不到就不猜
        runCatching { nm.notificationPolicy }.getOrNull()?.let { p ->
            val alarms = p.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
            val blocked = listOf(
                NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT to "全屏",
                NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK to "横幅",
                NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS to "指示灯",
                NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR to "状态栏图标",
                NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT to "息屏显示",
                NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST to "通知栏",
            ).filter { (bit, _) -> p.suppressedVisualEffects and bit != 0 }.joinToString("、") { it.second }
            appendLine("免打扰的规矩（开着时才起作用）：优先里闹钟${if (alarms) "放行" else "不放行"} · 屏蔽的视觉效果 ${blocked.ifEmpty { "没有" }}")
        }

        // 系统里此刻的样子，不是建的时候请求的：ROM 改过、你在设置里调过，都照实写
        appendLine("渠道（id · 重要性 · 声音 · 震动 · 越过免打扰 · 锁屏显示）：")
        listOf(Notifier.CHANNEL_ID, Notifier.GENTLE_CHANNEL_ID, Notifier.DAY_CHANNEL_ID).forEach { id ->
            val ch = nm.getNotificationChannel(id)
            if (ch == null) {
                appendLine("  $id · 还没建")
                return@forEach
            }
            val tone = when (ch.sound) {
                null -> "无"
                Settings.System.DEFAULT_ALARM_ALERT_URI -> "默认闹钟铃声"
                Settings.System.DEFAULT_NOTIFICATION_URI -> "默认通知音"
                else -> "别的铃声"
            }
            val stream = when (ch.audioAttributes?.usage) {
                null -> ""
                AudioAttributes.USAGE_ALARM -> "，走闹钟音量"
                AudioAttributes.USAGE_NOTIFICATION -> "，走通知音量"
                else -> "，usage ${ch.audioAttributes?.usage}"
            }
            val vibe = if (!ch.shouldVibrate()) "不震" else if (ch.vibrationPattern != null) "震（自定节奏）" else "震"
            val lock = when (ch.lockscreenVisibility) {
                android.app.Notification.VISIBILITY_PUBLIC -> "全显示"
                android.app.Notification.VISIBILITY_PRIVATE -> "藏内容"
                android.app.Notification.VISIBILITY_SECRET -> "不显示"
                else -> "跟系统"
            }
            appendLine("  $id · ${ch.importance} · $tone$stream · $vibe · ${if (ch.canBypassDnd()) "越过" else "不越过"} · $lock")
        }
        // 原样再写一遍：ROM 加的字段、mUserLockedFields（用户在设置里动过哪几样）都在里面，上面那几样解析不出来时看这个
        appendLine("渠道原样：")
        listOf(Notifier.CHANNEL_ID, Notifier.GENTLE_CHANNEL_ID, Notifier.DAY_CHANNEL_ID).forEach { id ->
            nm.getNotificationChannel(id)?.let { appendLine("  ${it.toString().take(1500)}") }
        }
    }

    /**
     * 只响不震。渠道说要震（上面那段），还要过这几关：手机有没有马达、系统的振动总开关和各用途的强度
     * （13 起闹钟、通知、来电分开调，调到 0 就不震）、ROM 自己的「响铃时振动」「静音时振动」。
     */
    private fun StringBuilder.vibration(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
        appendLine("马达 ${when {
            vibrator == null -> "读不到"
            !vibrator.hasVibrator() -> "没有"
            else -> "有 · 调幅度${if (vibrator.hasAmplitudeControl()) "能" else "不能"}"
        }}")
        val keys = listOf(
            "vibrate_on", "vibrate_when_ringing", "apply_ramping_ringer", "haptic_feedback_enabled",
            "alarm_vibration_intensity", "notification_vibration_intensity", "ring_vibration_intensity",
            "haptic_feedback_intensity", "hardware_haptic_feedback_intensity", "media_vibration_intensity",
            "vibrate_in_silent", "vibrate_in_normal",
        )
        appendLine("振动设置（System，没有这一项写 —）：" + keys.joinToString("、") { k ->
            "$k=${runCatching { Settings.System.getString(context.contentResolver, k) }.getOrNull() ?: "—"}"
        })
    }

    /**
     * 弹不弹全屏页。Android 这一层的许可（14 起）、渠道重要性要到 4（低于 4 系统不起全屏 intent）、
     * 锁屏通知设置。厂商自己的「后台弹出界面」「锁屏显示」查不到，只能看到点那一刻有没有「全屏页起来了」
     */
    private fun StringBuilder.fullScreen(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(KeyguardManager::class.java)
        val ch = nm.getNotificationChannel(Notifier.CHANNEL_ID)
        appendLine("全屏 intent 许可 ${if (Notifier.canUseFullScreenIntent(context)) "有" else "没有"}" +
            " · 响铃渠道重要性 ${ch?.importance ?: "还没建"}${if ((ch?.importance ?: 0) < NotificationManager.IMPORTANCE_HIGH) "（不到 4，全屏和横幅都不会出）" else ""}")
        appendLine("通知总开关 ${if (nm.areNotificationsEnabled()) "开" else "关"}" +
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) " · 被系统暂停 ${if (nm.areNotificationsPaused()) "是" else "否"}" else "") +
            " · app 重要性 ${nm.importance}")
        fun secure(k: String) = runCatching { Settings.Secure.getString(context.contentResolver, k) }.getOrNull() ?: "—"
        appendLine("锁屏通知 lock_screen_show_notifications=${secure("lock_screen_show_notifications")}、" +
            "lock_screen_allow_private_notifications=${secure("lock_screen_allow_private_notifications")}")
        appendLine("锁屏 ${if (km.isDeviceSecure) "有密码" else "没密码"} · 此刻 ${if (pm.isInteractive) "亮屏" else "灭屏"}${if (km.isKeyguardLocked) "锁着" else "没锁"}" +
            " · 省电模式 ${if (pm.isPowerSaveMode) "开" else "关"}")
    }

    /**
     * ROM 往系统设置里加的振动、免打扰、锁屏、横幅开关，名字各家不一样，挑名字里带这些词的全列出来。
     * 有的系统不让整表读，读不到就算了（上面那几段的固定几项照样有）
     */
    private fun StringBuilder.systemSettings(context: Context) {
        val words = listOf("vibrat", "haptic", "zen", "lock_screen", "lockscreen", "heads_up", "ringer", "dnd", "full_screen", "popup", "float")
        listOf("System" to Settings.System.CONTENT_URI, "Secure" to Settings.Secure.CONTENT_URI, "Global" to Settings.Global.CONTENT_URI)
            .forEach { (label, uri) ->
                val rows = readTable(context, uri).filter { (name, _) -> words.any { name.contains(it, ignoreCase = true) } }
                appendLine("$label 里相关的设置（${if (rows.isEmpty()) "读不到或没有" else "${rows.size} 项"}）：" +
                    rows.joinToString("、") { (k, v) -> "$k=${v.take(60)}" })
            }
    }

    private val BUILT_IN = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_TELEPHONY,
    )
    private val OUTPUTS = mapOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET to "有线耳机", AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "有线耳机",
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "蓝牙", AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "蓝牙通话",
        AudioDeviceInfo.TYPE_BLE_HEADSET to "蓝牙", AudioDeviceInfo.TYPE_BLE_SPEAKER to "蓝牙音箱",
        AudioDeviceInfo.TYPE_USB_HEADSET to "USB 耳机", AudioDeviceInfo.TYPE_USB_DEVICE to "USB",
    )

    private fun readTable(context: Context, uri: Uri): List<Pair<String, String>> = runCatching {
        context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    add(name to (c.getString(1) ?: "null"))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}
