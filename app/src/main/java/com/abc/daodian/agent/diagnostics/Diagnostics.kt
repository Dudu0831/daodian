package com.abc.daodian.agent.diagnostics

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.FileProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import com.abc.daodian.BuildConfig
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.model.provider.ProviderStore
import com.abc.daodian.shared.format.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 「导出诊断」：内测的人点一下，生成一份纯文本、交给系统分享（发微信给你）。
 *
 * **只写状态和数，不写内容**：没有对话、记忆、提醒的标题、账的金额商户、通知原文、key。
 * 各模块那一段由 [com.abc.daodian.agent.feature.Feature.diagnostics] 交上来，同样守这条。
 */
object Diagnostics {

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun time(millis: Long?): String = millis?.let { fmt.format(Date(it)) } ?: "—"

    /** 生成文件、弹分享。在主线程调 */
    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) { write(context) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diag", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "到点诊断 ${BuildConfig.VERSION_NAME}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "发给开发者"))
    }

    private suspend fun write(context: Context): File {
        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val now = System.currentTimeMillis()
        val file = File(dir, "daodian-diag-${SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date(now))}.txt")
        file.writeText(build(context, now))
        return file
    }

    private suspend fun build(context: Context, now: Long): String = buildString {
        appendLine("到点 · 诊断")
        appendLine("导出 ${time(now)}（${TimeZone.getDefault().id}）")
        appendLine("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）${if (BuildConfig.DEBUG) " debug" else ""}")
        appendLine("机型 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）· ${Build.DISPLAY}")
        val pm = context.getSystemService(PowerManager::class.java)
        appendLine("电池优化 ${if (pm.isIgnoringBatteryOptimizations(context.packageName)) "已忽略" else "受限"}")
        val am = context.getSystemService(AlarmManager::class.java)
        appendLine("精确闹钟 ${if (am.canScheduleExactAlarms()) "可以" else "不行"}")
        val next = am.nextAlarmClock
        appendLine("系统下一个闹钟 ${time(next?.triggerTime)} · 来自 ${next?.showIntent?.creatorPackage ?: "—"}")

        section("模型服务")
        val profile = runCatching { ProviderStore.flow(context).first() }.getOrNull()
        if (profile == null) appendLine("读不到")
        else appendLine(
            if (profile.isConfigured) "${profile.model} · ${Format.host(profile.baseUrl)} · key 已填 · 思考${if (profile.thinking) "开" else "关"}"
            else "没配好（网关、key、模型有一项空着）"
        )

        section("体检")
        FeatureRegistry.health(context).forEach {
            appendLine("${when (it.ok) { true -> "✓"; false -> "✗"; null -> "?" }} ${it.label}")
        }

        FeatureRegistry.diagnostics(context).forEach { (label, text) ->
            section(label)
            appendLine(text.trimEnd())
        }

        section("最近崩溃")
        val crashes = CrashLog.recent(context)
        if (crashes.isEmpty()) appendLine("没有")
        crashes.take(5).forEach {
            appendLine("--- ${it.name}")
            appendLine(it.readText().take(6000))
        }
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("== $title")
    }
}

/** 诊断里读库用：跑一句 SQL，每行按列转成字符串。只用来数数、看状态，别拿去读内容列 */
fun SupportSQLiteDatabase.rows(sql: String): List<List<String?>> =
    query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) })
        }
    }
