package com.abc.daodian.ui.quick

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 系统语音识别的一层薄壳。见 DESIGN.md §8.3
 *
 * 为什么不用 RecognizerIntent（对话页的麦克风走的就是它）：它要一个能接这个 intent 的 Activity，
 * 荣耀 MagicOS 上**一个都没有**（`cmd package query-activities -a android.speech.action.RECOGNIZE_SPEECH`
 * 查过，空的），按下去只会 ActivityNotFoundException。但系统默认识别服务在 ——
 * `settings get secure voice_recognition_service` 是 MagicVoice —— 所以直接绑 [SpeechRecognizer]。
 * 代价：要自己申请 RECORD_AUDIO；manifest 里还得用 `<queries>` 声明这个服务，
 * 否则 Android 11+ 的包可见性会让它绑不上。
 *
 * 所有方法都要在主线程调，回调也回在主线程。
 */
class VoiceInput(private val context: Context) {

    sealed interface Event {
        data object Ready : Event
        /** 边说边出来的字，每次都是到目前为止的全文 */
        data class Partial(val text: String) : Event
        /** 音量，已经归一到 0..1 */
        data class Level(val value: Float) : Event
        data class Final(val text: String) : Event
        data class Error(val code: Int, val message: String) : Event
    }

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    fun start(onEvent: (Event) -> Unit) {
        cancel()
        QuickTrace.log(context, "voice start")
        // 音量一秒十几次，不记；其余每一个回调都落进调试流水账
        val emit: (Event) -> Unit = { event ->
            if (event !is Event.Level) QuickTrace.log(context, "voice ← $event")
            onEvent(event)
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = emit(Event.Ready)
            override fun onBeginningOfSpeech() = QuickTrace.log(context, "voice ← beginningOfSpeech")
            // rmsdB 实测大致落在 -2..10，归一一下给墨晕用
            override fun onRmsChanged(rmsdB: Float) = emit(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = QuickTrace.log(context, "voice ← endOfSpeech")
            override fun onError(error: Int) = emit(Event.Error(error, messageOf(error)))
            override fun onResults(results: Bundle?) = emit(Event.Final(firstOf(results)))
            override fun onPartialResults(partialResults: Bundle?) {
                firstOf(partialResults).takeIf { it.isNotBlank() }?.let { emit(Event.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        )
    }

    /** 说完了：停止收音，结果随后从 [Event.Final] 回来 */
    fun stop() {
        QuickTrace.log(context, "voice stop")
        recognizer?.stopListening()
    }

    /** 不要结果了，连识别器一起拆掉。拆掉之后不会再有任何回调 */
    fun cancel() {
        if (recognizer != null) QuickTrace.log(context, "voice cancel")
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    private fun firstOf(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun messageOf(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再说一次？"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "语音服务连不上，点一下再试"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务正忙，稍等再说"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "语音服务听不了中文"
        else -> "语音识别出错了（$code），点一下再试"
    }
}
