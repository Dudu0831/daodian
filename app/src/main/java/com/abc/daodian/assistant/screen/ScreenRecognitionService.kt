package com.abc.daodian.assistant.screen

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * 占位：系统要求 voice-interaction-service 必须带一个 recognitionService，不然整个服务不认。
 * 我们不给别的 app 做语音识别，谁来调都直接回错。只有在设置 →「语音输入」里选了「到点」才会有人来调；
 * 我们自己的语音（速记、按住说话）是本地模型自己录音，不走这里。
 */
class ScreenRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) {}

    override fun onCancel(listener: Callback) {}
}
