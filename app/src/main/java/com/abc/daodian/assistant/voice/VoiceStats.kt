package com.abc.daodian.assistant.voice

import android.content.Context
import com.abc.daodian.shared.format.DiagnosticText
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「导出诊断」里的「语音」那一段：模型在不在、这会儿加载着没有、最近几次加载和说话的耗时。
 *
 * **只记数，不记内容**：说了什么、认成什么一个字都不留，只有时长、解了几次、多快、最后是出字 / 没听清 / 出错。
 * 内测的人说「好慢」「说了没反应」时，先看这里是加载慢、解得慢，还是根本没收到声音。
 * 最近 [KEEP] 行存在 SharedPreferences 里，进程被杀了也还在
 */
object VoiceStats {

    private const val PREFS = "voice_stats"
    private const val KEY = "recent"
    private const val KEEP = 12

    /** 这会儿内存里有几份模型（全 app 共用一份，正常是 0 或 1；放掉那一下新的又加载上了才会短暂是 2） */
    private val loaded = AtomicInteger(0)

    fun loadedOk(context: Context, ms: Long) {
        loaded.incrementAndGet()
        add(context, loadLine(System.currentTimeMillis(), ms, ok = true))
    }

    fun loadFailed(context: Context, ms: Long) {
        add(context, loadLine(System.currentTimeMillis(), ms, ok = false))
    }

    fun released() {
        loaded.updateAndGet { maxOf(0, it - 1) }
    }

    fun session(context: Context, hold: Boolean, heardMs: Long, decodes: Int, avgMs: Long, maxMs: Long, outcome: String) {
        add(context, sessionLine(System.currentTimeMillis(), hold, heardMs, decodes, avgMs, maxMs, outcome))
    }

    /** 诊断那一段的正文 */
    fun describe(context: Context, modelPresent: Boolean, audioDir: File): String {
        val files = audioDir.listFiles()?.filter { it.isFile }.orEmpty()
        return describe(
            modelPresent = modelPresent,
            loadedNow = loaded.get(),
            voiceNotes = files.size,
            voiceBytes = files.sumOf { it.length() },
            recent = recent(context)
        )
    }

    internal fun describe(modelPresent: Boolean, loadedNow: Int, voiceNotes: Int, voiceBytes: Long, recent: List<String>): String = buildString {
        appendLine("模型 $MODEL_NAME · ${if (modelPresent) "在包里" else "缺（没打进包，语音用不了）"}")
        appendLine("现在 ${if (loadedNow > 0) "加载着" else "没加载"}")
        appendLine("语音条 $voiceNotes 条 · ${voiceBytes / 1024} KB")
        if (recent.isEmpty()) appendLine("最近 没用过")
        else {
            appendLine("最近（新的在上）")
            recent.forEach { appendLine("  $it") }
        }
    }

    internal fun loadLine(at: Long, ms: Long, ok: Boolean) =
        "${DiagnosticText.time(at)} · ${if (ok) "加载" else "加载失败"} ${ms}ms"

    internal fun sessionLine(at: Long, hold: Boolean, heardMs: Long, decodes: Int, avgMs: Long, maxMs: Long, outcome: String) =
        "${DiagnosticText.time(at)} · ${if (hold) "按住" else "速记"} · ${"%.1f".format(heardMs / 1000f)} 秒 · " +
            (if (decodes == 0) "没解" else "解 $decodes 次 平均 ${avgMs}ms 最慢 ${maxMs}ms") + " · $outcome"

    private fun recent(context: Context): List<String> =
        prefs(context).getString(KEY, null)?.lines()?.filter { it.isNotBlank() }.orEmpty()

    @Synchronized
    private fun add(context: Context, line: String) {
        val next = (listOf(line) + recent(context)).take(KEEP)
        prefs(context).edit().putString(KEY, next.joinToString("\n")).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 包里带的那一版（`tools/fetch_asr_model.sh` 拉的），换模型时跟着改 */
    const val MODEL_NAME = "SenseVoice int8 2024-07-17 + Silero VAD"
}
