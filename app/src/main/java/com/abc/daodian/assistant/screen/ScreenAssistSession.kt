package com.abc.daodian.assistant.screen

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.widget.Toast
import com.abc.daodian.BuildConfig
import com.abc.daodian.assistant.quick.QuickTrace
import java.io.File

/** 系统按 xml/screen_assist.xml 里的 sessionService 来绑它，每次 [ScreenAssistService.look] 开一个会话 */
class ScreenAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ScreenAssistSession(this)
}

/**
 * 探路：只看系统交没交、交了多少。屏幕上的字（debug 包）落到 files/screen_probe.txt 好对照，
 * trace 里只记个数；弹一个 Toast 就收，不画界面、不交给模型。
 *
 *     adb shell "run-as com.abc.daodian.debug cat files/screen_probe.txt"
 */
class ScreenAssistSession(context: Context) : VoiceInteractionSession(context) {

    private val main = Handler(Looper.getMainLooper())
    private var texts = 0
    private var chars = 0
    private var shot: String = "没给"

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        QuickTrace.log(context, "screen session show flags=$showFlags")
        // 文字和截图各自异步回来，等一会儿一起报。没界面的会话窗口会挡住点击，报完就收
        main.postDelayed({
            Toast.makeText(context, "看到 $texts 段字（$chars 字），截图$shot", Toast.LENGTH_LONG).show()
            hide()
        }, 1500)
    }

    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure
        QuickTrace.log(
            context,
            "screen assist ${state.index + 1}/${state.count} app=${structure?.activityComponent?.packageName} " +
                "windows=${structure?.windowNodeCount ?: -1}"
        )
        if (structure == null) return
        val lines = mutableListOf<String>()
        for (i in 0 until structure.windowNodeCount) collect(structure.getWindowNodeAt(i).rootViewNode, lines)
        texts += lines.size
        chars += lines.sumOf { it.length }
        QuickTrace.log(context, "screen text nodes=${lines.size} chars=${lines.sumOf { it.length }}")
        if (BuildConfig.DEBUG) runCatching {
            File(context.filesDir, "screen_probe.txt")
                .writeText("${structure.activityComponent?.flattenToShortString()}\n\n" + lines.joinToString("\n"))
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        shot = screenshot?.let { " ${it.width}×${it.height}" } ?: "没给"
        QuickTrace.log(context, "screen screenshot=${screenshot?.let { "${it.width}x${it.height}" }}")
    }

    override fun onHide() {
        main.removeCallbacksAndMessages(null)
        super.onHide()
    }

    private fun collect(node: AssistStructure.ViewNode?, out: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount) collect(node.getChildAt(i), out)
    }
}
