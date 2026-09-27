package com.abc.daodian.agent.conversation

import android.content.Context
import com.abc.daodian.BuildConfig
import com.abc.daodian.agent.engine.Item
import com.abc.daodian.agent.engine.Turn
import com.abc.daodian.agent.engine.context.Compaction
import com.abc.daodian.agent.engine.context.FoldDrawings
import com.abc.daodian.agent.engine.context.FoldStale
import com.abc.daodian.agent.engine.context.Preamble
import com.abc.daodian.agent.model.LlmRequest
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 每次发给模型的是什么：垫没垫背景、摘要盖到哪一轮、原样带了哪几轮、折掉了几样。只在 debug 包里写。
 *
 * 这台荣耀 ROM 屏蔽第三方 app 的 logcat（见 CLAUDE.md），压缩生没生效只能落到文件里看：
 *
 *     adb shell run-as com.abc.daodian.debug cat files/context_trace.txt
 *
 * 超过 64KB 就从头写。对话页和桌面速记都记（速记没有摘要，只垫记忆）。
 */
object ContextTrace {

    private const val FILE = "context_trace.txt"
    private const val MAX_BYTES = 64 * 1024
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(context: Context, preamble: Preamble?, sent: List<Turn>, request: LlmRequest) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val bg = when {
                preamble == null -> "无"
                preamble.afterTurnId > 0 -> "${preamble.text.length} 字（摘要盖到 #${preamble.afterTurnId}）"
                else -> "${preamble.text.length} 字（只有记忆）"
            }
            val range = if (sent.isEmpty()) "-" else "#${sent.first().id}–#${sent.last().id}"
            val staleFolded = request.input.count { it is Item.ToolResult && it.output == FoldStale.FOLDED }
            val drawingsFolded = request.input.count { it is Item.AssistantMessage && FoldDrawings.FOLDED in it.text }
            val chars = Compaction.chars(sent) + (request.background?.length ?: 0)
            val line = "${LocalTime.now().format(clock)} 背景 $bg · 原样 ${sent.size} 轮 $range · " +
                "折掉查询 $staleFolded、图 $drawingsFolded · 约 $chars 字"
            val file = File(context.applicationContext.filesDir, FILE)
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText(line + "\n")
        }
    }
}
