package com.abc.daodian.ui.quick

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 桌面速记的语音输入：自己录音，在手机上用 sherpa-onnx 流式识别。见 DESIGN.md 决策 8.3
 *
 * 为什么不用系统的：`RecognizerIntent` 在荣耀 MagicOS 上没有 Activity 接；`SpeechRecognizer`
 * 倒是绑得上默认服务 MagicVoice（YOYO），它也真把麦克风打开了 —— 状态栏同时挂着「到点」和 YOYO ——
 * 但一个回调都不回，Ready / Error 都没有，纸就永远停在「在听」。它只伺候自家语音助手。
 * 供应商网关也没有转写接口（`/audio/transcriptions` 404，音频输入也不收），所以识别放在本地。
 *
 * 模型在 assets/asr/（中文流式 zipformer CTC int8，26MB），第一次 [start] 时才加载；
 * 加载那几百毫秒里录音照常进来、先攒着，模型好了一口气喂进去 —— 开头那几个字不会丢。
 *
 * 公开的方法都在主线程调，事件也回在主线程。
 */
class VoiceInput(private val context: Context) {

    sealed interface Event {
        data object Ready : Event
        /** 边说边出来的字，每次都是到目前为止的全文 */
        data class Partial(val text: String) : Event
        /** 音量，已经归一到 0..1 */
        data class Level(val value: Float) : Event
        /** 说完了。一个字都没认出来时是空串 */
        data class Final(val text: String) : Event
        data class Error(val message: String) : Event
    }

    /** 模型随包带着；缺了（没带 assets 就构建）就当这台手机没有语音 */
    val available: Boolean by lazy {
        context.assets.list(MODEL_DIR)?.contains(MODEL_FILE) == true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 一次只许一段录音在跑：前一段被 cancel 之后，要等它把麦克风和识别流都放掉，下一段才开始 */
    private val session = Mutex()
    private var engine: Deferred<Result<OnlineRecognizer>>? = null
    private var job: Job? = null
    @Volatile private var finishing = false

    fun start(onEvent: (Event) -> Unit) {
        cancel()
        QuickTrace.log(context, "voice start")
        finishing = false
        val loading = engine ?: scope.async(Dispatchers.Default) { load() }.also { engine = it }
        // 音量一秒十次，不记；其余每一个事件都落进调试流水账
        val emit: suspend (Event) -> Unit = { event ->
            withContext(Dispatchers.Main) {
                if (event !is Event.Level) QuickTrace.log(context, "voice ← $event")
                onEvent(event)
            }
        }
        job = scope.launch(Dispatchers.IO) {
            session.withLock { listen(loading, emit) }
        }
    }

    /** 说完了：停止收音，结果随后从 [Event.Final] 回来 */
    fun stop() {
        QuickTrace.log(context, "voice stop")
        finishing = true
    }

    /** 不要结果了。麦克风随后放掉，之后不会再有任何事件 */
    fun cancel() {
        if (job?.isActive == true) QuickTrace.log(context, "voice cancel")
        job?.cancel()
        job = null
    }

    /** 这个实例不再用了：停收音，等录音那边退出后把模型从内存里放掉 */
    fun release() {
        cancel()
        val loading = engine ?: return
        engine = null
        // 录音线程可能还在解最后一块，拆早了 native 那边会踩空指针 —— 拿到 session 锁再拆
        CoroutineScope(Dispatchers.Default).launch {
            session.withLock { loading.await().getOrNull()?.release() }
        }
    }

    private suspend fun listen(loading: Deferred<Result<OnlineRecognizer>>, emit: suspend (Event) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return emit(Event.Error("没有麦克风权限"))
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 2 * 4)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return emit(Event.Error(MIC_BUSY))
        }
        // 模型还没加载好时录下的声音
        val early = ArrayList<FloatArray>()
        suspend fun open(): Decoding? = loading.await().getOrNull()?.let { r ->
            Decoding(r).also { d -> early.forEach { d.feed(it) }; early.clear() }
        }
        var dec: Decoding? = null
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return emit(Event.Error(MIC_BUSY))
            emit(Event.Ready)
            val chunk = ShortArray(CHUNK)
            var said = ""
            while (!finishing) {
                coroutineContext.ensureActive()
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) return emit(Event.Error("录音出错了（$n），点一下再试"))
                if (n == 0) continue
                val samples = FloatArray(n) { chunk[it] / 32768f }
                emit(Event.Level(levelOf(samples)))
                if (dec == null) {
                    if (!loading.isCompleted) {
                        early += samples
                        continue
                    }
                    dec = open() ?: return emit(Event.Error(LOAD_FAILED))
                }
                val text = dec.feed(samples)
                if (text.isNotEmpty() && text != said) {
                    said = text
                    emit(Event.Partial(text))
                }
                if (dec.atEndpoint) {
                    QuickTrace.log(context, "voice endpoint")
                    break
                }
            }
            // 停顿够了，或者点了印。先把麦克风放了，再把尾巴解完
            record.stop()
            val d = dec ?: open() ?: return emit(Event.Error(LOAD_FAILED))
            dec = d
            emit(Event.Final(d.finish()))
        } finally {
            dec?.release()
            record.release()
        }
    }

    private fun load(): Result<OnlineRecognizer> {
        val t0 = System.nanoTime()
        return runCatching { OnlineRecognizer(context.assets, CONFIG) }
            .onSuccess { QuickTrace.log(context, "voice model loaded in ${(System.nanoTime() - t0) / 1_000_000}ms") }
            .onFailure { QuickTrace.log(context, "voice model failed: $it") }
    }

    /** 一段话的识别流 */
    private class Decoding(private val r: OnlineRecognizer) {
        private val s = r.createStream()

        /** 喂一块声音，返回到目前为止认出来的全文 */
        fun feed(samples: FloatArray): String {
            s.acceptWaveform(samples, RATE)
            while (r.isReady(s)) r.decode(s)
            return r.getResult(s).text.trim()
        }

        val atEndpoint: Boolean get() = r.isEndpoint(s)

        /** 补一小段静音把最后一个字推出来，再解完 */
        fun finish(): String {
            s.acceptWaveform(FloatArray(RATE * 3 / 10), RATE)
            s.inputFinished()
            while (r.isReady(s)) r.decode(s)
            return r.getResult(s).text.trim()
        }

        fun release() = s.release()
    }

    private fun levelOf(samples: FloatArray): Float {
        var sum = 0.0
        for (x in samples) sum += x * x
        // 均方根换成 dBFS：安静的屋里 -55 上下，凑近说话 -20 上下
        val db = 10 * log10(sum / samples.size + 1e-10)
        return ((db + 55) / 35).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val RATE = 16_000
        /** 一次读 100ms */
        const val CHUNK = RATE / 10
        const val MODEL_DIR = "asr"
        const val MODEL_FILE = "model.int8.onnx"
        const val MIC_BUSY = "麦克风打不开，可能正被别的 app 占着"
        const val LOAD_FAILED = "语音模型加载失败，去 app 里说吧"

        val CONFIG = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "$MODEL_DIR/$MODEL_FILE"),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2
            ),
            endpointConfig = EndpointConfig(
                // 还没开口：给 5 秒，纸长出来、人反应过来都要时间
                rule1 = EndpointRule(false, 5.0f, 0.0f),
                // 开了口，停 1.2 秒就收（「停一下就收」）
                rule2 = EndpointRule(true, 1.2f, 0.0f),
                // 一句最长 20 秒
                rule3 = EndpointRule(false, 0.0f, 20.0f)
            )
        )
    }
}
