package com.abc.daodian.runtime.model

import com.abc.daodian.runtime.engine.Item
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.StreamResponse
import com.openai.errors.OpenAIServiceException
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/**
 * 两种协议共用的「调一步」：流式为主，流没跑起来就退回一次性请求 —— 第三方兼容网关不一定实现 SSE。
 * 取消和回退的规矩见 DESIGN.md §6.2；各协议只管拼请求（[params]）、开流、收流（[Accumulator]）、一次性请求（[once]）。
 *
 * @param P 请求参数
 * @param E 流里的一个事件
 */
internal abstract class StreamingClient<P, E>(protected val profile: ProviderProfile) : LlmClient {

    override val model: String get() = profile.model

    protected val client: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .apiKey(profile.apiKey)
            .baseUrl(profile.baseUrl)
            .build()
    }

    /** 拼请求。带图的要读文件、转 base64，在 IO 线程上调。`internal` 只为测试看得到拼出来的样子 */
    internal abstract fun params(request: LlmRequest): P

    protected abstract fun openStream(params: P): StreamResponse<E>

    /** 一次性请求，阻塞到整段回完 */
    protected abstract fun once(params: P): StepOutput

    protected abstract fun accumulator(): Accumulator<E>

    /** 一条流收下来攒的东西 */
    protected interface Accumulator<E> {
        /** 流里自己报的错（没有状态码），收完时一条输出都没有才拿它报 */
        val failure: String?

        suspend fun consume(event: E, out: SendChannel<LlmEvent>)

        /**
         * null = 没有任何收全的输出。
         * [interrupted]：流断在半路，参数没收全的工具调用不能算 —— 办半截比不办更糟
         */
        fun output(interrupted: Boolean): StepOutput?
    }

    /**
     * 判定「流没跑起来」的两种情形：开流直接抛，或流开了一个事件都没有。
     * 流开到一半断掉不回退 —— 工具参数可能已经收全，重来一遍等于让模型再决定一次；
     * 这时按手上已经收全的条目判，一条都没收全才算失败。
     */
    override fun step(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        if (!profile.isConfigured) throw LlmException("还没配置供应商：${profile.redacted()}")
        val params = withContext(Dispatchers.IO) { params(request) }
        val withImages = request.input.any { it is Item.UserMessage && it.images.isNotEmpty() }
        /** 带着图、又是被网关退回来的：单独一种错，界面上另报一句（[ImagesRejectedException]） */
        fun failure(message: String, cause: Throwable?, rejected: Boolean): LlmException =
            if (withImages && rejected) ImagesRejectedException(message, cause) else LlmException(message, cause)
        val acc = accumulator()
        var streamed = false
        /** 流式没跑起来的原因。回退也失败时两个原因一起报，只报后一个会误导 */
        var streamError: String? = null
        val live = AtomicReference<StreamResponse<E>?>()

        val output: StepOutput? = try {
            detached(onCancel = { live.get()?.let { runCatching { it.close() } } }) {
                val stream = openStream(params)
                live.set(stream)
                try {
                    ensureActive()
                    val events = stream.stream().iterator()
                    while (events.hasNext()) {
                        ensureActive()
                        streamed = true
                        acc.consume(events.next(), this@channelFlow)
                    }
                } finally {
                    runCatching { stream.close() }
                }
            }
            if (streamed) {
                acc.output(interrupted = false) ?: throw failure(
                    acc.failure ?: "流结束了，但没有收到任何完整的输出", null,
                    rejected = acc.failure?.let(::mentionsImages) == true
                )
            } else null
        } catch (c: CancellationException) {
            throw c
        } catch (e: LlmException) {
            throw e
        } catch (t: Throwable) {
            if (streamed) acc.output(interrupted = true) ?: throw LlmException("流中断：${t.javaClass.simpleName}: ${t.message}", t)
            else null.also { streamError = "${t.javaClass.simpleName}: ${t.message}" }
        }

        if (output != null) {
            send(LlmEvent.Done(output))
        } else {
            send(LlmEvent.FellBack)
            val single = try {
                detached { once(params) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val first = streamError ?: "流开了但一个事件都没有"
                throw failure("流式：$first；回退一次性：${t.javaClass.simpleName}: ${t.message}", t, rejected = isRejection(t))
            }
            send(LlmEvent.Done(single))
        }
    }

    companion object {

        /** 阻塞 HTTP 活的去处：不挂在任何调用方下面，调用方取消了它也不会连坐着把调用方拖住 */
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 阻塞的 HTTP 调用放到不跟调用方一起取消的线程上跑，调用方只挂起等结果。
         *
         * 为什么绕这一下：SDK 的同步调用在响应头回来之前，没有任何能从外面掐断的句柄；
         * 协程取消又打断不了阻塞 IO。直接包在 withContext 里，点「停」就得陪着它干等 ——
         * 流式要等到首个 token，回退的一次性请求要等整段回完。异步版 SDK 也救不了：
         * `AsyncStreamResponse.close()` 只是等 future 落地后再关，重试链的 thenCompose 也不往下传取消。
         * （挂 `invokeOnCompletion` 也不行 —— 它在 Job **完成**时才触发，而 Job 正卡在阻塞调用里完成不了。）
         *
         * 取消时调用方立刻返回。[onCancel] 负责掐掉已经连上的流；还没连上的，
         * 由 [block] 连上后自己 ensureActive 发现、随手关掉。
         * 先标取消、再 onCancel：block 那边是先登记流、再查取消，两边交叉，谁晚到谁关，漏不掉。
         */
        private suspend fun <T> detached(onCancel: () -> Unit = {}, block: suspend CoroutineScope.() -> T): T {
            val work = io.async(block = block)
            try {
                return work.await()
            } catch (c: CancellationException) {
                work.cancel()
                onCancel()
                throw c
            }
        }

        /** 网关把请求退了回来：格式不认（400 / 415 / 422）、太大（413）。鉴权、限流、地址不对不算，那是别的毛病 */
        private fun isRejection(t: Throwable): Boolean = t is OpenAIServiceException && t.statusCode() in REJECTED

        private val REJECTED = setOf(400, 413, 415, 422)

        /** 流里报的错没有状态码，只能看它说没说到图 */
        private fun mentionsImages(reason: String): Boolean = reason.lowercase().let { r -> IMAGE_WORDS.any { it in r } }

        private val IMAGE_WORDS = listOf("image", "vision", "multimodal", "multi-modal", "图")

        /** 图读成 base64 的 data URL 直接带在请求里，不依赖网关的文件接口。读不到（文件没了）是 null */
        fun dataUrl(path: String): String? = runCatching {
            val file = File(path)
            val mime = if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
            "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes())
        }.getOrNull()

        /** 带图的一句里的文字：读不到的图换成一句话，别让模型对着一张不存在的图说话 */
        fun pictureText(item: Item.UserMessage, urls: List<String>): String {
            val missing = item.images.size - urls.size
            return item.content() + if (missing > 0) "\n（有 $missing 张图片读不到了）" else ""
        }
    }
}
