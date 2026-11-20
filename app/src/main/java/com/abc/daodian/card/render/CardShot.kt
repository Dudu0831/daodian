package com.abc.daodian.card.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
import com.abc.daodian.BuildConfig
import com.abc.daodian.shared.memory.NativeHeap

/**
 * 把一整页（[CardPage]）在看不见的 WebView 里画出来、截成图。见 DESIGN.md §8.5
 *
 * WebView 不挂到任何窗口上：量好卡片那么大、软件绘制，页面量完再 `draw` 到一张 Bitmap 上。留着一个接着用（[warm]）。
 * CSS 的 px 按这台手机的密度，所以 1px = 1dp，图是 dp × 密度那么大。
 * 断网和对话里画图那个沙盒一样（卡片模块不碰助手里面，这几行照抄）；不开 JS 桥：加载完由这边叫页面里的 `__measure()` 拿结果。
 * 同一页同一个大小画过的留几张（[cache]）：30 分钟兜底那次数没变，就不用再起一个 WebView。
 */
object CardShot {

    /** 能点的一块，dp，相对卡片左上角。[boxX]、[boxY] 是它里面方框 / 圈的中心：桌面当场画的勾对着它 */
    data class Tap(val action: String, val x: Float, val y: Float, val w: Float, val h: Float, val boxX: Float, val boxY: Float)

    /** [bitmap] 为 null = 没画出来，[problems] 里说为什么。画出来了也可能有 [problems]（裁边、脚本报错） */
    class Shot(val bitmap: Bitmap?, val taps: List<Tap>, val problems: List<String>, val text: String = "")

    /** 一页等多久：起 WebView + 加载 + 量，正常一秒内 */
    private const val TIMEOUT_MS = 6_000L

    /** 加载完再等多久才量、截：让字体、布局落定 */
    private const val SETTLE_MS = 80L

    /** 渲染进程没了时 [draw] 里等的那一句（正常的都是 JSON，以 { 开头） */
    private const val GONE = "gone"

    @Suppress("FunctionName")
    private fun EMPTY() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** 截过的图按字节算，最多 [CACHE_BYTES]：4×4 的一张就三四 MB，按张数算会攒出几十 MB */
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Shot>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Shot) = value.bitmap?.allocationByteCount ?: 1
    }

    private val mapper = ObjectMapper()

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 留着一个 WebView 接着用（一次只画一张，[lock]）：冷起一个 WebView 是一张图里最费时间的一段，点了勾要等它。
     * 闲 [IDLE_MS] 没人用就扔掉：WebView 背后有一个一百多 MB 的渲染进程，多半是在后台（他在桌面上点勾）时起的，
     * 后台占得越多越容易被荣耀挑中杀掉。扔的时候截图缓存一起清，再让原生堆把空页还给系统（[NativeHeap]）。
     * 画坏了（超时、渲染进程没了）也扔掉，下一张起新的
     */
    private var warm: WebView? = null

    private val lock = Mutex()

    /** 够连着勾几样清单 */
    private const val IDLE_MS = 20_000L

    private val idle = Runnable {
        warm?.destroy()
        warm = null
        cache.evictAll()
        val app = lastContext
        Thread { NativeHeap.purge().also { ok -> app?.let { trace(it, "WebView 扔了、缓存清了，空页还了：$ok") } } }.start()
    }

    /** 记 trace 用（只拿 applicationContext） */
    private var lastContext: Context? = null

    suspend fun shoot(context: Context, page: String, widthDp: Float, heightDp: Float): Shot {
        val key = "${page.hashCode()}:${page.length}:$widthDp:$heightDp"
        cache.get(key)?.let { return it }
        val started = SystemClock.uptimeMillis()
        val (shot, cold) = lock.withLock {
            withContext(Dispatchers.Main) {
                val first = draw(context.applicationContext, page, widthDp, heightDp)
                // 接着用的那个一直换不上新画面：它已经被扔了，起一个新的再画一次
                if (first.first.bitmap == null && !first.second && first.first.problems.any { STALE in it }) {
                    trace(context, "截到上一页的画面，换一个新 WebView 重画")
                    draw(context.applicationContext, page, widthDp, heightDp)
                } else first
            }
        }
        if (shot.bitmap != null) cache.put(key, shot)
        trace(context, "截图 ${widthDp.roundToInt()}×${heightDp.roundToInt()} ${SystemClock.uptimeMillis() - started}ms" + (if (cold) " 冷起" else "") + if (shot.bitmap == null) " 没画出来" else "")
        return shot
    }

    /** 每张图花了多久记一行到 `files/card_trace.txt`（只 debug 包；这台 ROM 看不到 logcat） */
    internal fun trace(context: Context, line: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = java.io.File(context.filesDir, "card_trace.txt")
            if (file.length() > 32 * 1024) file.delete()
            file.appendText("${java.time.LocalTime.now().withNano(0)} $line\n")
        }
    }

    /** 画一张。返回的第二样：这次是不是新起的 WebView */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun draw(context: Context, page: String, widthDp: Float, heightDp: Float): Pair<Shot, Boolean> {
        main.removeCallbacks(idle)
        lastContext = context
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).roundToInt().coerceAtLeast(1)
        val h = (heightDp * density).roundToInt().coerceAtLeast(1)
        val cold = warm == null
        val web = warm ?: WebView(context).also {
            sandbox(it)
            it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            warm = it
        }
        var ok = false
        try {
            web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, w, h)
            // 加载完（onPageFinished）再隔一小会儿，由这边叫页面里的 __measure()：看不见的 WebView 里页面自己的定时器不一定走
            var stage = "没加载完"
            // 左上角藏一个 2px 的记号，颜色每页随机：截完照它认是不是这一页（桌面裁圆角，正好裁掉）
            val mark = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val marked = page.replace(
                "</body>",
                "<i style=\"position:fixed;left:0;top:0;width:2px;height:2px;z-index:2147483647;background:rgb(${Color.red(mark)},${Color.green(mark)},${Color.blue(mark)})\"></i></body>"
            )
            val report: String = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = EMPTY()

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                        /**
                         * 渲染进程没了（内存紧时系统先杀它，或者它自己崩了）。不接这个、或者返回 false，整个 app 进程跟着崩。
                         * 正在画的这张算没画出来；闲着时没的，扔掉就是，下一张起新的
                         */
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (cont.isActive) cont.resume(GONE)
                            else {
                                if (warm === view) warm = null
                                view.destroy()
                            }
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            stage = "加载完了、量的时候没回话"
                            // 不能用 view.postDelayed：没挂到窗口上的 View，post 进去的要等挂上才跑（永远不跑）
                            main.postDelayed({
                                stage = "加载完了、叫了 __measure 没回话"
                                view.evaluateJavascript("(function(){try{return typeof __measure=='function'?__measure():'NOFN:'+typeof D}catch(e){return 'ERR:'+e}})()") { v ->
                                    stage = "回话了：${v?.take(120)}"
                                    val json = runCatching { mapper.readValue(v, String::class.java) }.getOrNull()
                                    if (json != null && json.startsWith("{") && cont.isActive) cont.resume(json)
                                }
                            }, SETTLE_MS)
                        }
                    }
                    web.loadDataWithBaseURL(null, marked, "text/html", "utf-8", null)
                }
            } ?: return Shot(null, emptyList(), listOf("网页 ${TIMEOUT_MS / 1000} 秒还没画完（$stage）")) to cold
            // 渲染进程没了：finally 里扔掉这个 WebView
            if (report == GONE) return Shot(null, emptyList(), listOf("截图用的 WebView 被系统收掉了（多半是内存紧），稍后再画")) to cold

            val r = runCatching { mapper.readTree(report) }.getOrNull()
            val problems = mutableListOf<String>()
            r?.path("errors")?.forEach { problems += "脚本报错：${it.asText().take(160)}" }
            r?.path("over")?.forEach { problems += "超出卡片：${it.asText()}，超出的部分桌面上看不到（是故意压出边的装饰，就给它加 data-bg 和 position:absolute）" }
            if (r?.path("empty")?.asBoolean(false) == true) problems += "画出来是空的"
            r?.path("overlaps")?.forEach { problems += "叠在一起：${it.asText()}，看不清（是故意垫在字后面的装饰，就给它加 data-bg）" }
            val text = r?.path("text")?.asText("").orEmpty()
            val taps = r?.path("taps")?.mapNotNull { t ->
                val a = t.path("a").asText("").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Tap(
                    a, t.path("x").floatValue(), t.path("y").floatValue(), t.path("w").floatValue(), t.path("h").floatValue(),
                    t.path("bx").floatValue(), t.path("by").floatValue()
                )
            }.orEmpty()

            // 页面说量完了，再把这一帧画出来。接着用的 WebView 有时还没换上新的一帧，draw 出来是上一页的画面：
            // 看左上角那个记号的颜色对不对，不对就隔一会儿再画（[MARK_TRIES] 次）
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var fresh = false
            for (i in 0 until MARK_TRIES) {
                bitmap.eraseColor(Color.TRANSPARENT)
                web.draw(Canvas(bitmap))
                if (marks(bitmap, mark)) { fresh = true; break }
                kotlinx.coroutines.delay(MARK_WAIT_MS)
            }
            if (!fresh) {
                bitmap.recycle()
                return Shot(null, taps, problems + "$STALE，稍后再画") to cold
            }
            if (blank(bitmap)) {
                bitmap.recycle()
                return Shot(null, taps, problems + "截出来是一片空白（WebView 没画出东西）") to cold
            }
            ok = true
            return Shot(bitmap, taps, problems, text) to cold
        } finally {
            if (ok) {
                main.postDelayed(idle, IDLE_MS)
            } else {
                web.stopLoading()
                web.destroy()
                warm = null
            }
        }
    }

    private const val MARK_TRIES = 12

    private const val STALE = "截到的不是这一页（WebView 没换上新画面）"

    private const val MARK_WAIT_MS = 40L

    private val rnd = java.util.Random()

    /** 左上角那一点是不是这一页的记号（软件画的颜色是准的，留两格余量） */
    private fun marks(b: Bitmap, mark: Int): Boolean {
        val p = b.getPixel(0, 0)
        return Color.alpha(p) > 250 && kotlin.math.abs(Color.red(p) - Color.red(mark)) <= 2 &&
            kotlin.math.abs(Color.green(p) - Color.green(mark)) <= 2 && kotlin.math.abs(Color.blue(p) - Color.blue(mark)) <= 2
    }

    /** 稀疏地看几十个点：全是透明的就是没画出来（页面底色默认是宣纸色，正常不会全透明） */
    private fun blank(b: Bitmap): Boolean {
        val stepX = (b.width / 12).coerceAtLeast(1)
        val stepY = (b.height / 12).coerceAtLeast(1)
        for (y in 0 until b.height step stepY) for (x in 0 until b.width step stepX) {
            if (Color.alpha(b.getPixel(x, y)) != 0) return false
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun sandbox(view: WebView) = view.apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            setSupportZoom(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // 系统字号调大时不跟着放大：卡片是按 dp 排的，放大就裁边
            textZoom = 100
        }
    }
}
