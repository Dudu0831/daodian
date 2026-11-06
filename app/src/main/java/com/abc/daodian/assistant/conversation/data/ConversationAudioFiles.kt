package com.abc.daodian.assistant.conversation.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 对话里的语音条，在本机怎么放（DESIGN.md §8.3）。
 *
 * 按住说话原地松手：这一次的原声编成 AAC（m4a，24kbps，一分钟不到 200KB），放进 [dir]，跟 `chat.db` 一样只增不减；
 * 只有喊停撤回那一句时删掉（字退回输入框，录音不留）。模型从来不听它，读的是认出来的字。
 *
 * 文件名里带着时长和波形（`…_7200_8a3f….m4a`：毫秒数，再一串十六进制、一个字一根墨条的高低），
 * 气泡画出来不用先解码，列表滚过去不卡（和图片名里带宽高一个道理）。
 */
object ConversationAudioFiles {

    private const val RATE = 16_000
    private const val BIT_RATE = 24_000
    /** 波形存多少根，画的时候按条宽重采样 */
    private const val PEAKS = 40

    private val META_IN_NAME = Regex("_(\\d+)_([0-9a-f]+)\\.m4a$")

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "chat_audio")

    /** [pcm]（16kHz 单声道 16 位小端）编好存下，返回路径。编不了（这台手机没有 AAC 编码器之类）返回 null */
    suspend fun save(context: Context, pcm: ByteArray): String? = withContext(Dispatchers.IO) {
        if (pcm.size < 2) return@withContext null
        val ms = pcm.size / 2 * 1000L / RATE
        val dir = dir(context).apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}-${(1000..9999).random()}_${ms}_${peaksOf(pcm)}.m4a")
        runCatching { encode(pcm, file) }
            .onFailure { file.delete() }
            .map { file.path }
            .getOrNull()
    }

    fun delete(path: String) {
        File(path).delete()
    }

    /** 时长（毫秒），从文件名里读。读不出来是 0 */
    fun durationMs(path: String): Long = META_IN_NAME.find(path)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** 波形，每根 0..1，从文件名里读。读不出来是空的 */
    fun peaks(path: String): FloatArray {
        val hex = META_IN_NAME.find(path)?.groupValues?.get(2) ?: return FloatArray(0)
        return FloatArray(hex.length) { hex[it].digitToInt(16) / 15f }
    }

    /** 每一小段的响度（均方根），按最响的那段归一，再开个方 —— 小声的也看得出起伏 */
    private fun peaksOf(pcm: ByteArray): String {
        val samples = pcm.size / 2
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val per = (samples / PEAKS).coerceAtLeast(1)
        val n = minOf(PEAKS, samples)
        val rms = FloatArray(n) { i ->
            var sum = 0.0
            val from = i * per
            val to = if (i == n - 1) samples else from + per
            for (k in from until to) {
                val v = buf.get(k) / 32768.0
                sum += v * v
            }
            sqrt(sum / (to - from).coerceAtLeast(1)).toFloat()
        }
        val top = rms.maxOrNull()?.takeIf { it > 1e-4f } ?: 1f
        return rms.joinToString("") { v ->
            (sqrt(abs(v) / top) * 15).roundToInt().coerceIn(0, 15).toString(16)
        }
    }

    private fun encode(pcm: ByteArray, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        // 按偶数个字节喂，别把一个样本劈成两半
                        val take = minOf(buf.remaining() and 1.inv(), pcm.size - offset)
                        buf.put(pcm, offset, take)
                        val pts = offset / 2 * 1_000_000L / RATE
                        offset += take
                        if (offset >= pcm.size) {
                            codec.queueInputBuffer(i, 0, take, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, take, pts, 0)
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && muxing) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
        check(muxing) { "AAC 编码器没给出格式" }
    }
}
