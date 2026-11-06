package com.abc.daodian.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.abc.daodian.BuildConfig
import com.abc.daodian.assistant.quick.QuickTrace
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 语音输入：自己录音，在手机上用 sherpa-onnx 识别。见 DESIGN.md 决策 8.3、VOICE_PLAN.md
 *
 * 两处在用，收法不一样：桌面速记停顿够了自己收；对话页是按住说话（[start] 的 `hold`），
 * 不看停顿，松手（[stop]）才收。
 *
 * 为什么不用系统的：`RecognizerIntent` 在荣耀 MagicOS 上没有 Activity 接；`SpeechRecognizer`
 * 倒是绑得上默认服务 MagicVoice（YOYO），它也真把麦克风打开了 —— 状态栏同时挂着「到点」和 YOYO ——
 * 但一个回调都不回，Ready / Error 都没有，纸就永远停在「在听」。它只伺候自家语音助手。
 * 供应商网关也没有转写接口（`/audio/transcriptions` 404，音频输入也不收），所以识别放在本地。
 *
 * **假流式**：SenseVoice 是非流式的（一整段一次算完，带标点、认中英日韩粤），所以边说边出字是拼出来的 ——
 * Silero VAD 按停顿把话切成段（一段最长 [SEGMENT_MAX_SECONDS] 秒），说完的段解一次就锁定；
 * 正在说的那段每 [REDECODE_MS] 毫秒整段重算一次，前几个字可能跟着改。真机上 4 线程解一段 8 秒内的话
 * 0.1–0.25 秒（VOICE_PLAN §1.1）。不切段的话 30 秒要算 1 秒，跟不上。
 *
 * 模型在 assets/asr/（SenseVoice int8 239MB + VAD 0.6MB，`tools/fetch_asr_model.sh` 拉），第一次 [start]
 * （或 [warmUp]）时才加载，真机约 2.5 秒；加载那会儿录音照常进来、先攒着，模型好了一口气喂进去 —— 开头那几个字不会丢。
 *
 * 公开的方法都在主线程调，事件也回在主线程。
 */
class VoiceInput(private val context: Context) {

    sealed interface Event {
        data object Ready : Event
        /** 边说边出来的字，每次都是到目前为止的全文。正在说的那段可能改字，锁定了的段不再变 */
        data class Partial(val text: String) : Event
        /** 音量，已经归一到 0..1 */
        data class Level(val value: Float) : Event
        /** 说完了。一个字都没认出来时是空串 */
        data class Final(val text: String) : Event
        data class Error(val message: String) : Event
    }

    /** 模型随包带着；缺了（没跑 fetch_asr_model.sh 就构建）就当这台手机没有语音 */
    val available: Boolean by lazy {
        context.assets.list(MODEL_DIR)?.let { MODEL_FILE in it && VAD_FILE in it } == true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 一次只许一段录音在跑：前一段被 cancel 之后，要等它把麦克风和识别流都放掉，下一段才开始 */
    private val session = Mutex()
    private var engine: Deferred<Result<Engine>>? = null
    private var job: Job? = null
    /** 闲着 [IDLE_RELEASE_MS] 之后把模型放掉的那个计时 */
    private var idle: Job? = null
    @Volatile private var finishing = false

    /** 先把模型加载上：对话页切到「按住说话」时调，第一次按下去就不用等那两三秒 */
    fun warmUp() {
        if (available && engine == null) engine = scope.async(Dispatchers.Default) { load() }
        armIdle()
    }

    /**
     * 开始收音。[hold] 是按住说话：不看停顿，一直收到 [stop]，最长 [HOLD_MAX_SECONDS] 秒；
     * 否则停顿够了自己收。
     */
    fun start(hold: Boolean = false, onEvent: (Event) -> Unit) {
        cancel()
        QuickTrace.log(context, if (hold) "voice start (hold)" else "voice start")
        finishing = false
        val loading = engine ?: scope.async(Dispatchers.Default) { load() }.also { engine = it }
        // 音量一秒十次，不记；字一秒好几次，也不记（只记最后那句和解码耗时）
        val emit: suspend (Event) -> Unit = { event ->
            withContext(Dispatchers.Main) {
                if (event !is Event.Level && event !is Event.Partial) QuickTrace.log(context, "voice ← $event")
                onEvent(event)
            }
        }
        idle?.cancel()
        job = scope.launch(Dispatchers.IO) {
            session.withLock { listen(loading, hold, emit) }
        }.also { it.invokeOnCompletion { scope.launch { armIdle() } } }
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

    /**
     * 停收音，等录音那边退出后把模型从内存里放掉（SenseVoice 跑起来 500MB 上下，VOICE_PLAN §2.3）。
     * 放掉之后这个实例照样能用：下次 [warmUp] / [start] 再加载，加载那会儿录音先攒着
     */
    fun release() {
        idle?.cancel()
        idle = null
        cancel()
        val loading = engine ?: return
        engine = null
        QuickTrace.log(context, "voice model released")
        // 录音线程可能还在解最后一段，拆早了 native 那边会踩空指针 —— 拿到 session 锁再拆
        CoroutineScope(Dispatchers.Default).launch {
            session.withLock { loading.await().getOrNull()?.release() }
        }
    }

    /** 模型在内存里、又没在收音：[IDLE_RELEASE_MS] 之后放掉 */
    private fun armIdle() {
        idle?.cancel()
        if (engine == null || job?.isActive == true) return
        idle = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (job?.isActive != true) release()
        }
    }

    private suspend fun listen(
        loading: Deferred<Result<Engine>>,
        hold: Boolean,
        emit: suspend (Event) -> Unit
    ) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return emit(Event.Error("没有麦克风权限"))
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // 解一段的那零点几秒里不读麦克风，缓冲留 2 秒，声音不会丢
            maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE * 2 * 2)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return emit(Event.Error(MIC_BUSY))
        }
        // 模型还没加载好时录下的声音
        val early = ArrayList<FloatArray>()
        suspend fun open(): Transcript? = loading.await().getOrNull()?.let { e ->
            Transcript(e).also { t -> early.forEach { t.feed(it, live = false) }; early.clear() }
        }
        var tr: Transcript? = null
        // debug 包把这一次的原声留下来（files/voice_last.wav），认错了好拿去对
        val raw = if (BuildConfig.DEBUG) ByteArrayOutputStream(RATE * 2 * 10) else null
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return emit(Event.Error(MIC_BUSY))
            emit(Event.Ready)
            val chunk = ShortArray(CHUNK)
            var said = ""
            var heard = 0
            while (!finishing) {
                coroutineContext.ensureActive()
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) return emit(Event.Error("录音出错了（$n），再试一次"))
                if (n == 0) continue
                heard += n
                raw?.let { out -> for (k in 0 until n) { val v = chunk[k].toInt(); out.write(v and 0xff); out.write(v shr 8 and 0xff) } }
                // 按住不放也有个头：手机揣兜里压着了，不能一直录下去
                if (hold && heard > RATE * HOLD_MAX_SECONDS) {
                    QuickTrace.log(context, "voice hold limit")
                    break
                }
                val samples = FloatArray(n) { chunk[it] / 32768f }
                emit(Event.Level(levelOf(samples)))
                if (tr == null) {
                    if (!loading.isCompleted) {
                        early += samples
                        continue
                    }
                    tr = open() ?: return emit(Event.Error(LOAD_FAILED))
                }
                val text = tr.feed(samples, live = true)
                if (text.isNotEmpty() && text != said) {
                    said = text
                    emit(Event.Partial(text))
                }
                // 按住说话不看停顿：什么时候收由手指定，旁边一直有人说话也不会收不了
                if (!hold && tr.atEndpoint) {
                    QuickTrace.log(context, "voice endpoint")
                    break
                }
            }
            // 停顿够了、点了印，或者松手了。先把麦克风放了，再把尾巴解完
            record.stop()
            val t = tr ?: open() ?: return emit(Event.Error(LOAD_FAILED))
            tr = t
            val text = t.finish()
            QuickTrace.log(context, "voice decodes ${t.stats}")
            t.segments.forEach { QuickTrace.log(context, "voice seg $it") }
            emit(Event.Final(text))
        } finally {
            record.release()
            tr?.let { QuickTrace.log(context, "voice vad ${it.vadStats}") }
            raw?.let { runCatching { writeWav(File(context.filesDir, "voice_last.wav"), it.toByteArray()) } }
        }
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    private fun load(): Result<Engine> {
        val t0 = System.nanoTime()
        return runCatching { Engine(OfflineRecognizer(context.assets, ASR_CONFIG), Vad(context.assets, VAD_CONFIG)) }
            .onSuccess { QuickTrace.log(context, "voice model loaded in ${(System.nanoTime() - t0) / 1_000_000}ms") }
            .onFailure { QuickTrace.log(context, "voice model failed: $it") }
    }

    private class Engine(val asr: OfflineRecognizer, val vad: Vad) {
        fun release() {
            vad.release()
            asr.release()
        }
    }

    /**
     * 一次说话：VAD 切段、说完的段锁定、正在说的那段隔一会儿整段重算。
     *
     * 样本都按「这一次说话的第几个样本」记：VAD 交出的段带着起点，起点加长度就是它在哪儿结束，
     * 正在说的那段从上一段结束的地方算起 —— 段和段之间不重、不漏。
     */
    private class Transcript(private val e: Engine) {
        private val locked = StringBuilder()
        private var live = ""
        /** 上一段结束之后的声音，从第 [pendingStart] 个样本算起 */
        private var pending = FloatArray(RATE * 4)
        private var pendingLen = 0
        private var pendingStart = 0L
        /** 喂给 VAD 的样本要凑够一个窗口 */
        private val window = FloatArray(VAD_WINDOW)
        private var windowLen = 0
        private var fed = 0L
        private var lastDecodeAt = 0L
        private var speaking = false
        private var spoke = false
        private var lastSpeechAt = 0L

        private var speechChunks = 0
        private var chunks = 0
        private var peak = 0f
        /** debug 流水账用：每一段从第几秒起、多长、认成了什么 */
        val segments = ArrayList<String>()

        private var decodes = 0
        private var decodeMs = 0L
        private var decodeMaxMs = 0L

        init {
            e.vad.reset()
        }

        /** 速记的收法：还没开口 5 秒、开了口停 1.2 秒、一句满 20 秒 */
        val atEndpoint: Boolean
            get() = when {
                !spoke -> fed > RATE * 5L
                fed > RATE * 20L -> true
                else -> !speaking && fed - lastSpeechAt > RATE * 12L / 10
            }

        val vadStats: String
            get() = "speech=$speechChunks/$chunks chunks, peak=${"%.3f".format(peak)}"

        val stats: String
            get() = if (decodes == 0) "n=0" else "n=$decodes avg=${decodeMs / decodes}ms max=${decodeMaxMs}ms"

        /** 喂一块声音，返回到目前为止的全文（锁定的 + 正在说的）。[live] 为假时只切段、不重算正在说的那段 */
        fun feed(samples: FloatArray, live: Boolean): String {
            appendPending(samples)
            var i = 0
            while (i < samples.size) {
                val take = minOf(VAD_WINDOW - windowLen, samples.size - i)
                samples.copyInto(window, windowLen, i, i + take)
                windowLen += take
                i += take
                fed += take
                if (windowLen == VAD_WINDOW) {
                    e.vad.acceptWaveform(window.copyOf())
                    windowLen = 0
                }
            }
            drain()
            speaking = e.vad.isSpeechDetected()
            chunks++
            for (x in samples) if (kotlin.math.abs(x) > peak) peak = kotlin.math.abs(x)
            if (speaking) speechChunks++
            if (speaking) {
                spoke = true
                lastSpeechAt = fed
            } else {
                this.live = ""
                // 没人说话时只留开口前那一小截，别攒一长串静音去重算
                keepTail(PREROLL)
            }
            if (live && speaking && pendingLen >= MIN_LIVE && fed - lastDecodeAt >= RATE * REDECODE_MS / 1000) {
                lastDecodeAt = fed
                this.live = decode(pending.copyOf(pendingLen))
            }
            return join(locked.toString(), this.live)
        }

        /** 收尾：把攒着的半个窗口和还没说完的那段都交出来，返回最后的全文 */
        fun finish(): String {
            if (windowLen > 0) {
                e.vad.acceptWaveform(window.copyOf(windowLen))
                windowLen = 0
            }
            e.vad.flush()
            drain()
            live = ""
            return locked.toString()
        }

        /** VAD 说完了一段：解一次就锁定，正在说的那段从它结束的地方重新算 */
        private fun drain() {
            while (!e.vad.empty()) {
                val seg = e.vad.front()
                e.vad.pop()
                val text = decode(seg.samples)
                if (BuildConfig.DEBUG) segments += "@%.1fs %.1fs → %s".format(seg.start / RATE.toFloat(), seg.samples.size / RATE.toFloat(), text)
                if (text.isNotEmpty()) {
                    val joined = join(locked.toString(), text)
                    locked.setLength(0)
                    locked.append(joined)
                }
                dropBefore(seg.start.toLong() + seg.samples.size)
                live = ""
                lastDecodeAt = fed
            }
        }

        private fun decode(x: FloatArray): String {
            val t0 = System.nanoTime()
            val s = e.asr.createStream()
            try {
                s.acceptWaveform(x, RATE)
                e.asr.decode(s)
                return e.asr.getResult(s).text.trim()
            } finally {
                s.release()
                val ms = (System.nanoTime() - t0) / 1_000_000
                decodes++
                decodeMs += ms
                if (ms > decodeMaxMs) decodeMaxMs = ms
            }
        }

        private fun appendPending(x: FloatArray) {
            if (pendingLen + x.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + x.size))
            x.copyInto(pending, pendingLen)
            pendingLen += x.size
        }

        /** 扔掉第 [index] 个样本之前的 */
        private fun dropBefore(index: Long) {
            val drop = (index - pendingStart).coerceIn(0L, pendingLen.toLong()).toInt()
            if (drop == 0) return
            pending.copyInto(pending, 0, drop, pendingLen)
            pendingLen -= drop
            pendingStart += drop
        }

        private fun keepTail(n: Int) {
            if (pendingLen > n * 2) dropBefore(pendingStart + pendingLen - n)
        }
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
        /** 按住说话最长收这么久 */
        const val HOLD_MAX_SECONDS = 60
        /** 模型加载着、闲了这么久就放掉 */
        const val IDLE_RELEASE_MS = 60_000L
        /** 正在说的那段隔这么久重算一次 */
        const val REDECODE_MS = 400L
        /** 一段最长这么久，到了 VAD 强行切开：段越长重算越慢 */
        const val SEGMENT_MAX_SECONDS = 8f
        /** 正在说的那段至少攒这么多才重算 */
        const val MIN_LIVE = RATE / 5
        /** 没人说话时留着的开口前那一截 */
        const val PREROLL = RATE / 2
        const val VAD_WINDOW = 512
        const val MODEL_DIR = "asr"
        const val MODEL_FILE = "model.int8.onnx"
        const val VAD_FILE = "silero_vad.onnx"
        const val MIC_BUSY = "麦克风打不开，可能正被别的 app 占着"
        const val LOAD_FAILED = "语音模型加载失败，去 app 里说吧"

        val ASR_CONFIG = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$MODEL_DIR/$MODEL_FILE",
                    // 中、英、日、韩、粤自己认
                    language = "auto",
                    // 「三点半」写成 3:30、「五十块」写成 50
                    useInverseTextNormalization = true
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 4
            )
        )

        val VAD_CONFIG = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$MODEL_DIR/$VAD_FILE",
                threshold = 0.5f,
                // 停 0.25 秒算一段说完：切得细，锁定得快；速记的「停 1.2 秒就收」另外数
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = SEGMENT_MAX_SECONDS
            ),
            sampleRate = RATE,
            numThreads = 1
        )

        /** 两段接起来：后一段是英文、数字开头，前一段是英文、数字或英文标点结尾，就补一个空格；中文直接接 */
        fun join(a: String, b: String): String = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            b.first().isAsciiWord() && (a.last().isAsciiWord() || a.last() in ".,!?;:") -> "$a $b"
            else -> a + b
        }

        private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
