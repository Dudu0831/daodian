package com.abc.daodian.assistant.conversation

import android.content.Context
import com.abc.daodian.BuildConfig
import com.abc.daodian.assistant.AssistantAgent
import com.abc.daodian.runtime.Agent
import com.abc.daodian.runtime.context.Compaction
import com.abc.daodian.runtime.context.FoldDrawings
import com.abc.daodian.runtime.context.FoldImages
import com.abc.daodian.runtime.context.FoldStale
import com.abc.daodian.runtime.context.Preamble
import com.abc.daodian.runtime.engine.Item
import com.abc.daodian.runtime.engine.Turn
import com.abc.daodian.runtime.model.LlmRequest
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
 * 超过 64KB 就从头写。对话页和桌面速记都记（速记没有摘要，只垫记忆和模块现状）。
 * 别的 agent（整理员、派活、记忆整理员）各记各的：`files/trace_<id>.txt`。每行带上提示词版本。
 * 由 runtime 在每次请求前调（`DaodianApp` 装进去的 Tracer）。
 */
object ContextTrace {

    private const val FILE = "context_trace.txt"
    private const val MAX_BYTES = 64 * 1024
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(context: Context, agent: Agent, preamble: Preamble?, sent: List<Turn>, request: LlmRequest) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val bg = when {
                preamble == null -> "无"
                preamble.afterTurnId > 0 -> "${preamble.text.length} 字（摘要盖到 #${preamble.afterTurnId}）"
                else -> "${preamble.text.length} 字（没有摘要）"
            }
            val range = if (sent.isEmpty()) "-" else "#${sent.first().id}–#${sent.last().id}"
            val staleFolded = request.input.count { it is Item.ToolResult && it.output == FoldStale.FOLDED }
            val drawingsFolded = request.input.count { it is Item.AssistantMessage && FoldDrawings.FOLDED in it.text }
            // 他发来的图片：这次原样带了几张、折成一句的有几句
            val pictures = request.input.sumOf { (it as? Item.UserMessage)?.images?.size ?: 0 }
            val picturesFolded = request.input.count { it is Item.UserMessage && FoldImages.MARK in it.text }
            val chars = Compaction.chars(sent) + (request.background?.length ?: 0)
            val line = "${LocalTime.now().format(clock)} ${agent.prompt.version} 背景 $bg · 原样 ${sent.size} 轮 $range · " +
                "折掉查询 $staleFolded、图 $drawingsFolded · 带图片 $pictures 张、折掉 $picturesFolded 句 · 约 $chars 字"
            val name = if (agent.info.id == AssistantAgent.id) FILE else "trace_${agent.info.id}.txt"
            val file = File(context.applicationContext.filesDir, name)
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText(line + "\n")
        }
    }
}
