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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 语音输入：自己录音，在手机上用 sherpa-onnx 识别。见 DESIGN.md 决策 8.3
 *
 * 两处在用，收法见 [Mode]：对话页按住说话，松手（[stop]）才收；桌面速记点印（也是 [stop]）才收，
 * 停顿不收，只兜两种忘了收的情况。模型全 app 共用一份，见 [VoiceEngine]。
 *
 * 为什么不用系统的：`RecognizerIntent` 在荣耀 MagicOS 上没有 Activity 接；`SpeechRecognizer`
 * 倒是绑得上默认服务 MagicVoice（YOYO），它也真把麦克风打开了 —— 状态栏同时挂着「到点」和 YOYO ——
 * 但一个回调都不回，Ready / Error 都没有，纸就永远停在「在听」。它只伺候自家语音助手。
 * 供应商网关也没有转写接口（`/audio/transcriptions` 404，音频输入也不收），所以识别放在本地。
 *
 * **假流式 + 整句重认**：SenseVoice 是非流式的（一整段一次算完，带标点、认中英日韩粤）。按住说话边说边出字是拼出来的 ——
 * Silero VAD 按停顿把话切成段（一段 [SEGMENT_MAX_SECONDS] 秒上下），说完的段先不锁，和正在说的那段一起
 * 每 [REDECODE_MS] 毫秒重算一次（有下文才认得准）；停够 [LOCK_PAUSE] 或这一截超过 [TAIL_MAX] 才锁定（11-10）。
 * 真机上 4 线程解 10 秒内的话 0.1–0.3 秒（§8.3）。不切段的话 30 秒要算 1 秒，跟不上。
 * 收尾时整句（按 [FULL_PIECE_SECONDS] 秒分块）再认一遍，**发出去的是这一遍**：一两秒的半截话单独认不准。
 * 速记不边说边出字，只切段看你停没停，点了印整句认一次。
 *
 * 模型在 assets/asr/（SenseVoice int8 239MB + VAD 0.6MB，`tools/fetch_asr_model.sh` 拉），第一次 [start]
 * （或 [warmUp]）时才向 [VoiceEngine] 借，没加载的话真机约 2.5 秒；VAD 几十毫秒就好，先切着段，
 * 大模型好了再补认 —— 开头那几个字不会丢。
 *
 * 公开的方法都在主线程调，事件也回在主线程。
 */
class VoiceInput(private val context: Context) {

    sealed interface Event {
        data object Ready : Event
        /**
         * 说完了（[stop]），可模型还没加载完：录音已经停了，字要等模型好了才认得出来。
         * 刚装完头一回能要五六秒，不说一声看着就像点了没反应
         */
        data object Preparing : Event
        /**
         * 边说边出来的字，每次都是到目前为止的全文。正在说的那段可能改字，锁定了的段不再变：
         * 前 [locked] 个字是锁定的，后面是正在说的那段（对话页拿它画两层灰）
         */
        data class Partial(val text: String, val locked: Int = text.length) : Event
        /** 音量，已经归一到 0..1 */
        data class Level(val value: Float) : Event
        /**
         * 说完了。一个字都没认出来时是空串。按住说话的那种（`Mode.Hold`）还带着这一次的原声 [pcm]：
         * 16kHz 单声道 16 位小端，对话页拿去存成语音条；速记那种是 null
         */
        class Final(val text: String, val pcm: ByteArray? = null) : Event {
            override fun toString() = "Final(text=$text, pcm=${pcm?.size ?: 0}B)"
        }
        data class Error(val message: String) : Event
    }

    /** 怎么收 */
    enum class Mode {
        /** 对话页按住说话：不看停顿，松手才收，最长 [MAX_SECONDS] 秒 */
        Hold,
        /**
         * 桌面速记：点印才收，停顿不收。兜两种忘了收的：一直没开口 [SILENT_START_SECONDS] 秒（揣兜里误碰了）收掉、
         * 说过话之后连着静 [LONG_SILENCE_SECONDS] 秒算说完了；最长 [MAX_SECONDS] 秒
         */
        Tap
    }

    /** 模型随包带着；缺了（没跑 fetch_asr_model.sh 就构建）就当这台手机没有语音 */
    val available: Boolean by lazy { modelPresent(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 向 [VoiceEngine] 借着的那一份；null = 没借 */
    private var engine: VoiceEngine.Engine? = null
    private var job: Job? = null
    /** 闲着 [IDLE_RELEASE_MS] 之后把模型放掉的那个计时 */
    private var idle: Job? = null
    @Volatile private var finishing = false
    /** 这一次的结果（Final / Error）已经交出去了：之后的 [cancel] 只是收场，不算「取消」 */
    private var delivered = false

    /** 先把模型加载上：对话页切到「按住说话」时调，第一次按下去就不用等那两三秒 */
    fun warmUp() {
        if (available) borrow()
        armIdle()
    }

    /** 开始收音，什么时候收见 [mode] */
    fun start(mode: Mode, onEvent: (Event) -> Unit) {
        cancel()
        QuickTrace.log(context, if (mode == Mode.Hold) "voice start (hold)" else "voice start (tap)")
        finishing = false
        delivered = false
        val loading = borrow()
        // 音量一秒十次，不记；字一秒好几次，也不记（只记最后那句和解码耗时）
        val emit: suspend (Event) -> Unit = { event ->
            withContext(Dispatchers.Main) {
                if (event !is Event.Level && event !is Event.Partial) QuickTrace.log(context, "voice ← $event")
                if (event is Event.Final || event is Event.Error) delivered = true
                onEvent(event)
            }
        }
        idle?.cancel()
        job = scope.launch(Dispatchers.IO) {
            VoiceEngine.session.withLock { listen(loading, mode, emit) }
        }.also { it.invokeOnCompletion { scope.launch { armIdle() } } }
    }

    /** 说完了：停止收音，结果随后从 [Event.Final] 回来 */
    fun stop() {
        QuickTrace.log(context, "voice stop")
        finishing = true
    }

    /** 不要结果了。麦克风随后放掉，之后不会再有任何事件 */
    fun cancel() {
        if (job?.isActive == true && !delivered) QuickTrace.log(context, "voice cancel")
        job?.cancel()
        job = null
    }

    /**
     * 停收音，把借的模型还给 [VoiceEngine]：没别人借着的话，[VoiceEngine.GRACE_MS] 之后从内存里放掉
     * （SenseVoice 跑起来 500MB 上下，§8.3）。还了之后这个实例照样能用：下次 [warmUp] / [start] 再借
     */
    fun release() {
        idle?.cancel()
        idle = null
        cancel()
        if (engine == null) return
        engine = null
        VoiceEngine.letGo(context)
    }

    private fun borrow(): VoiceEngine.Engine =
        engine ?: VoiceEngine.acquire(context).also { engine = it }

    /** 借着模型、又没在收音：[IDLE_RELEASE_MS] 之后还掉 */
    private fun armIdle() {
        idle?.cancel()
        if (engine == null || job?.isActive == true) return
        idle = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (job?.isActive != true) release()
        }
    }

    private suspend fun listen(
        loading: VoiceEngine.Engine,
        mode: Mode,
        emit: suspend (Event) -> Unit
    ) {
        val hold = mode == Mode.Hold
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return emit(Event.Error("没有麦克风权限"))
        }
        // 诊断里那一行（VoiceStats）：说了多久、解得多快、最后怎样。被 cancel 的就是「取消」
        var outcome = "取消"
        var heard = 0
        suspend fun fail(message: String) {
            outcome = "出错：$message"
            emit(Event.Error(message))
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
            return fail(MIC_BUSY)
        }
        // 速记不边说边出字：模型两三秒才加载好，等它的那一截字出不来，看着像卡住了；
        // 不如只画波形和计时，点了印整句认一次（§8.3）。按住说话拖到「转文字」要看字，照旧边说边认
        val live = hold
        // VAD 还没加载好时录下的声音（几十毫秒）
        val early = ArrayList<FloatArray>()
        suspend fun open(): Transcript? = loading.vad.await().getOrNull()?.let { vad ->
            Transcript(vad).also { t -> early.forEach { t.feed(it, live = false) }; early.clear() }
        }
        var tr: Transcript? = null
        // 按住说话的原声要存成语音条；debug 包还把每一次的原声留下来（files/voice_last.wav），认错了好拿去对
        val raw = if (hold || BuildConfig.DEBUG) ByteArrayOutputStream(RATE * 2 * 10) else null
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return fail(MIC_BUSY)
            emit(Event.Ready)
            val chunk = ShortArray(CHUNK)
            var said = ""
            // 字没变、只是正在说的那段锁定了，也得再报一次：对话页那截字要从浅灰变深
            var saidLocked = 0
            while (!finishing) {
                coroutineContext.ensureActive()
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) return fail("录音出错了（$n），再试一次")
                if (n == 0) continue
                heard += n
                raw?.let { out -> for (k in 0 until n) { val v = chunk[k].toInt(); out.write(v and 0xff); out.write(v shr 8 and 0xff) } }
                // 按住不放、点印前也有个头：手机揣兜里压着了，不能一直录下去
                if (heard > RATE * MAX_SECONDS) {
                    QuickTrace.log(context, "voice limit ${MAX_SECONDS}s")
                    break
                }
                val samples = FloatArray(n) { chunk[it] / 32768f }
                emit(Event.Level(levelOf(samples)))
                if (tr == null) {
                    if (!loading.vad.isCompleted) {
                        early += samples
                        continue
                    }
                    tr = open() ?: return fail(LOAD_FAILED)
                }
                // 大模型好了就接上；没好之前只切段，切好的段等它好了再补认
                if (tr.asr == null && loading.asr.isCompleted) tr.asr = loading.asr.await().getOrNull() ?: return fail(LOAD_FAILED)
                val text = tr.feed(samples, live = live)
                if (text.isNotEmpty() && (text != said || tr.lockedLength != saidLocked)) {
                    said = text
                    saidLocked = tr.lockedLength
                    emit(Event.Partial(text, tr.lockedLength))
                }
                // 按住说话不看停顿：什么时候收由手指定，旁边一直有人说话也不会收不了。
                // 速记也不看一般的停顿（说一半想一想不该被收掉），只兜忘了点印的
                val endpoint = if (mode == Mode.Tap) tapEndpoint(tr.spoke, tr.fed, tr.lastSpeechAt, tr.nearSpeaking) else null
                if (endpoint != null) {
                    QuickTrace.log(context, "voice endpoint: $endpoint")
                    break
                }
            }
            // 点了印、松手了，或者兜底收了。先把麦克风放了，再把尾巴解完
            record.stop()
            // 速记这时候才开始认，告诉纸一声；按住说话只有模型还没好时才说
            if (!live || !loading.asr.isCompleted) emit(Event.Preparing)
            val t = tr ?: open() ?: return fail(LOAD_FAILED)
            tr = t
            t.asr = t.asr ?: loading.asr.await().getOrNull() ?: return fail(LOAD_FAILED)
            val text = t.finish()
            QuickTrace.log(context, "voice decodes ${t.stats}")
            t.segments.forEach { QuickTrace.log(context, "voice seg $it") }
            outcome = if (text.isBlank()) "没听清" else "出字"
            emit(Event.Final(text, if (hold) raw?.toByteArray() else null))
        } finally {
            record.release()
            tr?.let { QuickTrace.log(context, "voice vad ${it.vadStats}") }
            val (n, avg, max) = tr?.numbers ?: Triple(0, 0L, 0L)
            VoiceStats.session(context, hold, heard * 1000L / RATE, n, avg, max, outcome)
            if (BuildConfig.DEBUG) raw?.let { runCatching { writeWav(File(context.filesDir, "voice_last.wav"), it.toByteArray()) } }
        }
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    /**
     * 一次说话：VAD 切段、没锁的那一截（说完没锁的几段 + 正在说的）隔一会儿重算、停够了才锁（[settle]）—— 这些只为边说边出字。
     * **发出去的字是收尾时整句重认的**（[finish]）：段是按停顿（0.5 秒）切的，一两秒的半截话单独认，
     * 没有上下文、语种也常判错，段的头尾还被 VAD 削掉一点；整句一次认准得多，10 秒的话多花 0.3 秒上下。
     *
     * 声音整段留着（最长 60 秒，4MB 不到），样本都按「这一次说话的第几个样本」记。
     * [asr] 没接上之前只切段；接上之后补认切好的段（边说边出字时）。
     */
    private class Transcript(private val vad: Vad) {
        /** 大模型，加载好了才接上 */
        var asr: OfflineRecognizer? = null
        private var audio = FloatArray(RATE * 10)
        /** VAD 切出来的段：起止样本 */
        private val segs = ArrayList<LongArray>()
        /** 每段多响（[loudness]，dBFS），和 [segs] 一一对应 */
        private val segDb = ArrayList<Float>()
        /** 这一次里最响的那段：近讲门拿它当「你的音量」，比它轻 [GATE_DB] 以上的当成远处别人在说 */
        private var refDb = Float.NEGATIVE_INFINITY
        /** 这会儿在说话，而且够响（不是远处的人声）。速记看停没停用它 */
        var nearSpeaking = false
            private set
        /** 前几段已经认过、锁进 [locked] 了。后面切好还没锁的几段是「尾巴」，和正在说的那段一起重算 */
        private var lockedSegs = 0
        private val locked = StringBuilder()
        /** 没锁的那一截（尾巴 + 正在说的）最近一次认成了什么 */
        private var tailText = ""
        /** [tailText] 是哪一截声音认出来的；锁尾巴时起止对得上就不用再认一遍 */
        private var tailFrom = -1L
        private var tailTo = -1L
        /** 尾巴变了（新切出一段、刚锁掉一批），[tailText] 该重认了 */
        private var dirty = false
        /** 这一回开口是从第几个样本起（VAD 认出在说话的那一刻，真开口还要早 0.3 秒上下） */
        private var speechStart = 0L
        /** 上一次重算花了多久，下一次至少隔它的两倍：录音那条协程一半时间得留着读麦克风 */
        private var lastTailMs = 0L
        /** 全文里前多少个字是锁定的（全文 = 锁定的 + 正在说的，前一截原样） */
        val lockedLength: Int get() = locked.length
        /** 正在说的那段从第几个样本算起（上一段结束的地方，没人说话时只留开口前一小截） */
        private var pendingStart = 0L
        /** 喂给 VAD 的样本要凑够一个窗口 */
        private val window = FloatArray(VAD_WINDOW)
        private var windowLen = 0
        private var lastDecodeAt = 0L
        /** 一共喂了多少样本 */
        var fed = 0L
            private set
        /** 这会儿在说话 */
        var speaking = false
            private set
        /** 开过口 */
        var spoke = false
            private set
        /** 最后一次听见说话时 [fed] 是多少 */
        var lastSpeechAt = 0L
            private set

        private var speechChunks = 0
        private var chunks = 0
        private var peak = 0f
        /** debug 流水账用：每一段从第几秒起、多长、认成了什么 */
        val segments = ArrayList<String>()

        private var decodes = 0
        private var decodeMs = 0L
        private var decodeMaxMs = 0L
        /** 收尾整句重认花了多久 */
        private var fullMs = 0L

        init {
            vad.reset()
        }

        val vadStats: String
            get() = "speech=$speechChunks/$chunks chunks, peak=${"%.3f".format(peak)}"

        /** 解了几次、平均、最慢（毫秒），给诊断 */
        val numbers: Triple<Int, Long, Long>
            get() = Triple(decodes, if (decodes == 0) 0L else decodeMs / decodes, decodeMaxMs)

        val stats: String
            get() = if (decodes == 0) "n=0" else "n=$decodes avg=${decodeMs / decodes}ms max=${decodeMaxMs}ms full=${fullMs}ms"

        /** 喂一块声音，返回到目前为止的全文（锁定的 + 正在说的）。[live] 为假时只切段、不认字，返回空串 */
        fun feed(samples: FloatArray, live: Boolean): String {
            if (fed + samples.size > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, (fed + samples.size).toInt()))
            samples.copyInto(audio, fed.toInt())
            var i = 0
            while (i < samples.size) {
                val take = minOf(VAD_WINDOW - windowLen, samples.size - i)
                samples.copyInto(window, windowLen, i, i + take)
                windowLen += take
                i += take
                fed += take
                if (windowLen == VAD_WINDOW) {
                    vad.acceptWaveform(window.copyOf())
                    windowLen = 0
                }
            }
            drain()
            val wasSpeaking = speaking
            speaking = vad.isSpeechDetected()
            chunks++
            for (x in samples) if (kotlin.math.abs(x) > peak) peak = kotlin.math.abs(x)
            if (speaking) speechChunks++
            // 最近半秒够不够响。还没有说完的段可比时，听见的都算
            nearSpeaking = speaking && near(loudness(audio, maxOf(0L, fed - RATE / 2).toInt(), fed.toInt()))
            if (nearSpeaking) {
                spoke = true
                lastSpeechAt = fed
            }
            if (speaking) {
                if (!wasSpeaking) speechStart = fed
            } else {
                // 没人说话时只留开口前那一小截，别拿一长串静音去重算
                pendingStart = maxOf(pendingStart, fed - PREROLL)
            }
            if (!live) return ""
            val asr = asr ?: return ""
            settle(asr, force = false)
            // 没锁的那一截从尾巴的头算起；没有尾巴就只是正在说的那段
            val from = if (lockedSegs < segs.size) segs[lockedSegs][0] else pendingStart
            when {
                speaking -> {
                    val gap = maxOf(RATE * REDECODE_MS / 1000, lastTailMs * 2 * RATE / 1000)
                    // 刚开口的那零点几秒不认：声音太短，语种常判错，头几个字先错后改
                    if (fed - speechStart >= MIN_LIVE && fed - lastDecodeAt >= gap) {
                        // 正在说的这段明显比你轻：多半是旁边的人，先不出字
                        val far = !near(loudness(audio, pendingStart.toInt(), fed.toInt()))
                        decodeTail(asr, from, fed, if (far) pendingStart to fed else null)
                    }
                }
                lockedSegs == segs.size -> tailText = ""
                // 这段说完了：按 VAD 给的起止再认一次，停够了就照这一遍锁
                dirty -> decodeTail(asr, from, segs.last()[1])
            }
            // 没锁的字不带句尾标点：模型每认一次都在末尾补一个句号，话没说完它就一直跳
            return join(locked.toString(), unsettled(tailText))
        }

        private fun decodeTail(asr: OfflineRecognizer, from: Long, to: Long, hush: Pair<Long, Long>? = null) {
            lastDecodeAt = fed
            val t0 = System.nanoTime()
            tailText = decode(asr, from, to, hush)
            lastTailMs = (System.nanoTime() - t0) / 1_000_000
            tailFrom = from
            tailTo = to
            dirty = false
        }

        /** 收尾：把攒着的半个窗口和还没说完的那段都交出来，整句重认，返回最后的全文。[asr] 得先接上 */
        fun finish(): String {
            val asr = checkNotNull(asr)
            if (windowLen > 0) {
                vad.acceptWaveform(window.copyOf(windowLen))
                windowLen = 0
            }
            vad.flush()
            drain()
            val t0 = System.nanoTime()
            var text = ""
            if (BuildConfig.DEBUG) segs.forEachIndexed { i, s ->
                segments += "段 @%.1fs %.1fs %.0fdB%s".format(
                    s[0] / RATE.toFloat(), (s[1] - s[0]) / RATE.toFloat(), segDb[i], if (near(segDb[i])) "" else " 轻，不认"
                )
            }
            // 近讲门：比最响那段轻太多的段不认（夹在中间的，认的时候抹成静音，见 decode）
            val mine = segs.filterIndexed { i, _ -> near(segDb[i]) }
            for ((from, to) in fullPieces(mine.map { it[0] to it[1] }, fed)) {
                val piece = decode(asr, from, to)
                if (BuildConfig.DEBUG) segments += "整句 @%.1fs %.1fs → %s".format(from / RATE.toFloat(), (to - from) / RATE.toFloat(), piece)
                text = join(text, piece)
            }
            fullMs = (System.nanoTime() - t0) / 1_000_000
            if (text.isNotBlank()) return text
            // 整句什么都没认出来、分段倒认出了点什么：拿分段的
            settle(asr, force = true)
            return locked.toString()
        }

        /** VAD 说完了一段：记下起止，进尾巴；下一段从它结束的地方算 */
        private fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val start = seg.start.toLong().coerceIn(0L, fed)
                val end = (start + seg.samples.size).coerceAtMost(fed)
                segs += longArrayOf(start, end)
                val db = loudness(audio, start.toInt(), end.toInt())
                segDb += db
                if (db > refDb) {
                    refDb = db
                    // 「你的音量」变了，哪些段算轻跟着变：上一回认的那截不能直接拿来锁
                    tailFrom = -1
                }
                pendingStart = maxOf(pendingStart, end)
                dirty = true
            }
        }

        /**
         * 尾巴什么时候锁：说完的段不马上锁，留着和后面的话一起重算（有了下文，前面的字才认得准，
         * 也更接近收尾整句认的那一遍）。两种时候锁 —— 停够了 [LOCK_PAUSE]（一句说完了），
         * 或者连着正在说的超过 [TAIL_MAX]（再长重算跟不上）。一批最长 [TAIL_MAX]，几段并在一起认一次。
         * 后面紧跟着还有话的，句号降成逗号：那是停顿切出来的，不是句子完了。[force]：收尾，全锁
         */
        private fun settle(asr: OfflineRecognizer, force: Boolean) {
            while (lockedSegs < segs.size) {
                val first = segs[lockedSegs][0]
                val lastEnd = segs.last()[1]
                val paused = !speaking && fed - lastEnd >= LOCK_PAUSE
                val tooLong = (if (speaking) fed else lastEnd) - first > TAIL_MAX
                if (!force && !paused && !tooLong) return
                var j = lockedSegs
                while (j + 1 < segs.size && segs[j + 1][1] - first <= TAIL_MAX) j++
                val end = segs[j][1]
                var text = when {
                    // 这一批全是远处的人声
                    (lockedSegs..j).none { near(segDb[it]) } -> ""
                    first == tailFrom && end == tailTo -> tailText
                    else -> decode(asr, first, end)
                }
                val more = j < segs.lastIndex || speaking
                val gapAfter = (if (j < segs.lastIndex) segs[j + 1][0] else fed) - end
                if (more && gapAfter < LOCK_PAUSE) text = soften(text)
                if (BuildConfig.DEBUG) segments += "@%.1fs %.1fs → %s".format(first / RATE.toFloat(), (end - first) / RATE.toFloat(), text)
                if (text.isNotEmpty()) {
                    val joined = join(locked.toString(), text)
                    locked.setLength(0)
                    locked.append(joined)
                }
                lockedSegs = j + 1
                tailText = ""
                tailFrom = -1
                tailTo = -1
                dirty = true
                // 还在说：剩下那截这一刷就重认，不然正在说的字要空一拍
                if (speaking) {
                    lastDecodeAt = 0
                    lastTailMs = 0
                }
            }
        }

        private fun near(db: Float) = db >= refDb - GATE_DB

        /** 认 [from, to) 这一截。里面轻的段（远处的人声）和 [hush] 那一截先抹成静音，模型就听不见 */
        private fun decode(asr: OfflineRecognizer, from: Long, to: Long, hush: Pair<Long, Long>? = null): String {
            val t0 = System.nanoTime()
            val clip = audio.copyOfRange(from.toInt(), to.toInt())
            fun mute(a: Long, b: Long) {
                val lo = (maxOf(a, from) - from).toInt()
                val hi = (minOf(b, to) - from).toInt()
                if (lo < hi) clip.fill(0f, lo, hi)
            }
            for (i in segs.indices) if (!near(segDb[i])) mute(segs[i][0], segs[i][1])
            hush?.let { mute(it.first, it.second) }
            val s = asr.createStream()
            try {
                s.acceptWaveform(clip, RATE)
                asr.decode(s)
                return asr.getResult(s).text.trim()
            } finally {
                s.release()
                val ms = (System.nanoTime() - t0) / 1_000_000
                decodes++
                decodeMs += ms
                if (ms > decodeMaxMs) decodeMaxMs = ms
            }
        }
    }

    private fun levelOf(samples: FloatArray): Float {
        var sum = 0.0
        for (x in samples) sum += x * x
        // 均方根换成 dBFS：安静的屋里 -55 上下，凑近说话 -20 上下
        val db = 10 * log10(sum / samples.size + 1e-10)
        return ((db + 55) / 35).toFloat().coerceIn(0f, 1f)
    }

    internal companion object {
        const val RATE = 16_000
        /** 一次读 100ms */
        const val CHUNK = RATE / 10
        /** 一次最长收这么久（按住说话、速记都是）。满了当说完了 */
        const val MAX_SECONDS = 60
        /** 速记：一直没开口这么久，收掉（没听清） */
        const val SILENT_START_SECONDS = 8
        /** 速记：说过话之后连着静这么久，当说完了（忘了点印） */
        const val LONG_SILENCE_SECONDS = 10

        /**
         * 速记兜底的两种收法，到了返回原因（写进 trace），没到返回 null。
         * 样本数都从这一次说话开头算，[fed] 是到现在为止喂了多少
         */
        fun tapEndpoint(spoke: Boolean, fed: Long, lastSpeechAt: Long, speaking: Boolean): String? = when {
            !spoke -> if (fed > RATE.toLong() * SILENT_START_SECONDS) "silent start" else null
            !speaking && fed - lastSpeechAt > RATE.toLong() * LONG_SILENCE_SECONDS -> "long silence"
            else -> null
        }
        /** 模型加载着、闲了这么久就放掉 */
        const val IDLE_RELEASE_MS = 60_000L
        /** 正在说的那段隔这么久重算一次 */
        const val REDECODE_MS = 400L
        /** 一段最长这么久，到了 VAD 强行切开：段越长重算越慢 */
        const val SEGMENT_MAX_SECONDS = 8f
        /** 收尾整句重认时，一块最长这么久（不切的话 60 秒要解 2 秒多，块越长越慢） */
        const val FULL_PIECE_SECONDS = 20
        /** 整句重认时说话前后各多带这么一截：VAD 给的段头尾会削掉轻声的字 */
        const val FULL_PAD = RATE * 3 / 10
        /** VAD 认出开口之后再攒这么多才出第一版字（加上 VAD 自己慢的那 0.3 秒，等于说了 0.7 秒上下） */
        const val MIN_LIVE = RATE * 2 / 5
        /** 说完的段停够这么久才锁定：一句说完了。不到这么久又接着说的，和后面的话一起重算 */
        const val LOCK_PAUSE = RATE.toLong()
        /** 没锁的那一截（尾巴 + 正在说的）最长这么久，超了先把尾巴锁掉：10 秒解一次 0.3 秒上下 */
        const val TAIL_MAX = RATE * 10L
        /**
         * 近讲门：比这一次里最响的那段轻这么多（dB）以上的，当成远处别人在说，不认、也不算你还在说。
         * 嘴离手机十几厘米、别人在一两米外，差 15–20dB；自己一句话里轻重差不到这么多
         */
        const val GATE_DB = 12f

        /**
         * 这一截有多响（dBFS）：切成 50ms 一格，取第八成响的那一格 —— 一截里夹着的停顿不拉低它，
         * 一两下特别响的（咳嗽、碰到手机）也不抬高它。太短算不出来时返回很小的数
         */
        fun loudness(audio: FloatArray, from: Int, to: Int): Float {
            val frame = RATE / 20
            val n = (to - from) / frame
            if (n <= 0) return -100f
            val db = FloatArray(n) { i ->
                var sum = 0.0
                val base = from + i * frame
                for (k in base until base + frame) sum += audio[k] * audio[k]
                (10 * log10(sum / frame + 1e-10)).toFloat()
            }
            db.sort()
            return db[(n * 4 / 5).coerceAtMost(n - 1)]
        }

        private const val TRAILING_MARKS = "。，、；：？！.,;:?!…"

        /** 还没锁的字怎么给人看：去掉末尾的标点 */
        fun unsettled(text: String): String = text.trimEnd { it in TRAILING_MARKS || it == ' ' }

        /** 后面紧跟着还有话时锁定的一截：末尾的句号降成逗号（问号、叹号留着） */
        fun soften(text: String): String = when {
            text.endsWith("。") -> text.dropLast(1) + "，"
            text.endsWith(".") && !text.endsWith("..") -> text.dropLast(1) + ","
            else -> text
        }
        /** 没人说话时留着的开口前那一截 */
        const val PREROLL = RATE / 2
        const val VAD_WINDOW = 512
        const val MODEL_DIR = "asr"
        const val MODEL_FILE = "model.int8.onnx"
        const val VAD_FILE = "silero_vad.onnx"
        /** 模型文件在不在包里（诊断也看这个） */
        fun modelPresent(context: Context): Boolean =
            context.assets.list(MODEL_DIR)?.let { MODEL_FILE in it && VAD_FILE in it } == true

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
                // 停 0.5 秒算一段说完。0.25 秒切得太碎，边说边出的字一两秒一段地认，错字多；
                // 再长锁定就慢、正在说的那段重算也慢（11-09）。速记什么时候收另外数（tapEndpoint）
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = SEGMENT_MAX_SECONDS
            ),
            sampleRate = RATE,
            numThreads = 1
        )

        /**
         * 收尾整句重认怎么分块：从第一段说话到最后一段，前后各多带 [FULL_PAD]；超过 [FULL_PIECE_SECONDS] 秒的
         * 要切开（一段最长 8 秒，所以总切得开）。切在**停得最久**的那个空隙 —— 大多是句子之间，不切断一句话；
         * 只在块够半长之后的空隙里挑，不然开头一个长停顿会切出一小块。同样长的挑靠后的，块少一点。
         * 没有说话的段就什么都不认。[segs] 是 VAD 切出来的段（起止样本，按先后），[total] 是一共录了多少样本
         */
        fun fullPieces(segs: List<Pair<Long, Long>>, total: Long): List<Pair<Long, Long>> {
            if (segs.isEmpty()) return emptyList()
            val max = RATE.toLong() * FULL_PIECE_SECONDS
            val groups = ArrayList<List<Pair<Long, Long>>>()
            var i = 0
            while (i < segs.size) {
                val start = segs[i].first
                // 这一块最多装到第 j 段
                var j = i
                while (j + 1 < segs.size && segs[j + 1].second - start <= max) j++
                if (j == segs.lastIndex) {
                    groups += segs.subList(i, segs.size)
                    break
                }
                // 在第 k 段后面切（k 在 i..j），挑停得最久的
                val candidates = (i..j).filter { segs[it].second - start >= max / 2 }.ifEmpty { (i..j).toList() }
                val k = candidates.maxWith(compareBy<Int> { segs[it + 1].first - segs[it].second }.thenBy { it })
                groups += segs.subList(i, k + 1)
                i = k + 1
            }
            return groups.mapIndexed { i, g ->
                var from = maxOf(0L, g.first().first - FULL_PAD)
                if (i > 0) from = maxOf(from, (groups[i - 1].last().second + g.first().first) / 2)
                var to = minOf(total, g.last().second + FULL_PAD)
                if (i < groups.lastIndex) to = minOf(to, (g.last().second + groups[i + 1].first().first) / 2)
                from to to
            }
        }

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
