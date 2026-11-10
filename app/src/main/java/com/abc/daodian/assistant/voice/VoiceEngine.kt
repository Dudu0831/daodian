package com.abc.daodian.assistant.voice

import android.content.Context
import com.abc.daodian.assistant.quick.QuickTrace
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全 app 只有一份语音模型（SenseVoice 跑起来 540–700MB，§8.3）。对话页和桌面速记都从这里借：
 * [acquire] 借、[letGo] 还；没人借了再等 [GRACE_MS] 才真的放掉。
 *
 * 为什么不各管各的：速记转对话页（问卡、打开 app）时，两边各加载一份，内存里一下子两份；
 * 说错了关掉速记马上再点一次墨印，又要从头等两三秒。
 *
 * 宽限期内模型还在后台占着几百 MB，所以只等 10 秒 —— 够「关了马上再点」「速记交给对话页」，
 * 又不至于让荣耀在后台为这个杀进程。
 *
 * 公开的方法都在主线程调。
 */
internal object VoiceEngine {

    /** 没人借了，再留这么久才放掉 */
    const val GRACE_MS = 10_000L

    /**
     * 借到的那一份。VAD 只有 0.6MB、几十毫秒就好，SenseVoice 要两三秒，分开加载：
     * VAD 一好就能切段、看你停没停（速记兜底收靠它），不用等大模型
     */
    class Engine(val vad: Deferred<Result<Vad>>, val asr: Deferred<Result<OfflineRecognizer>>)

    /**
     * 一次只许一段录音在跑（麦克风本来也只有一个）：前一段被取消之后，要等它把麦克风和识别流都放掉，下一段才开始。
     * 拆模型也要拿这把锁 —— 录音线程可能还在解最后一段，拆早了 native 那边会踩空指针
     */
    val session = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: Engine? = null
    private var holders = 0
    private var unload: Job? = null

    /** 借一份：还没加载就开始加载。两个 Deferred 加载完才有结果，加载那会儿录音照常先攒着 */
    fun acquire(context: Context): Engine {
        holders++
        unload?.cancel()
        unload = null
        val app = context.applicationContext
        return engine ?: Engine(
            vad = scope.async(Dispatchers.Default) { runCatching { Vad(app.assets, VoiceInput.VAD_CONFIG) } },
            asr = scope.async(Dispatchers.Default) { loadAsr(app) }
        ).also { engine = it }
    }

    /** 还回来。没人借了，[GRACE_MS] 之后放掉；这期间又有人借就不放 */
    fun letGo(context: Context) {
        holders = maxOf(0, holders - 1)
        if (holders > 0 || engine == null) return
        unload?.cancel()
        val app = context.applicationContext
        unload = scope.launch {
            delay(GRACE_MS)
            if (holders > 0) return@launch
            val loading = engine ?: return@launch
            engine = null
            unload = null
            QuickTrace.log(app, "voice model released")
            launch(Dispatchers.Default) {
                session.withLock {
                    loading.vad.await().getOrNull()?.release()
                    loading.asr.await().getOrNull()?.let {
                        it.release()
                        VoiceStats.released()
                    }
                }
                // 模型的几百 MB 释放了，分配器还攥着空页不还；马上还给系统，别等它几分钟后自己收
                QuickTrace.log(app, "voice heap purged=${NativeHeap.purge()}")
            }
        }
    }

    private fun loadAsr(context: Context): Result<OfflineRecognizer> {
        val t0 = System.nanoTime()
        return runCatching { OfflineRecognizer(context.assets, VoiceInput.ASR_CONFIG) }
            .onSuccess {
                val ms = (System.nanoTime() - t0) / 1_000_000
                QuickTrace.log(context, "voice model loaded in ${ms}ms")
                VoiceStats.loadedOk(context, ms)
            }
            .onFailure {
                QuickTrace.log(context, "voice model failed: $it")
                VoiceStats.loadFailed(context, (System.nanoTime() - t0) / 1_000_000)
            }
    }
}
