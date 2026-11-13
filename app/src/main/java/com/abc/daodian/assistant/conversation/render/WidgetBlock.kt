package com.abc.daodian.assistant.conversation.render

import android.os.SystemClock
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.abc.daodian.shared.theme.DaodianColors
import com.abc.daodian.shared.theme.DaodianType
import com.abc.daodian.shared.theme.DarkPalette

/*
 * 模型画的东西：```html 代码块（会动、能点的小组件），以前的 ```svg 图也在这里画。见 DESIGN.md §6.8
 *
 * 放进一个 WebView 里跑：开 JS，但断网（blockNetworkLoads + CSP + 拦掉一切请求）、不给文件 / content 访问、
 * 不让跳转，空源加载。里面的数出不去，外面的东西也拉不进来。
 * 桥只有三个口：报高度（气泡跟着定高）、报错（收成一行；刚说完的那一回合还会让模型重画一次）、
 * say（替他在对话里说一句，只认手指刚点过之后的）。
 *
 * 深色下它写死的颜色照 [SvgColors] 换一遍。
 * 滚出屏幕不销毁，留最近几个活的（[Live]）。没做的：点开全屏 —— 组件本来就铺满宽度，放大没多少用。
 */

/** 模型没报高度之前先占这么高 */
private const val FIRST_H = 220f

/** 再高就截住，免得一个组件占掉几屏 */
private const val MAX_H = 560f

/** 滚出去再滚回来，直接按上次量到的高度占位，列表不跳 */
private val heights = LruCache<String, Float>(24)

/**
 * 图 / 组件画坏了往哪报（解析不了、脚本报错、画出来是空的）。对话页只给刚说完的那一回合接上，
 * 接上的会让模型自动重画一次（[com.abc.daodian.assistant.conversation.ConversationViewModel.drawingBroken]）；历史里的不接，只退回原文。
 */
internal val LocalDrawingBroken = compositionLocalOf<((String) -> Unit)?> { null }

/** 组件里调了 `say("…")`：等于他在对话里说了这一句。没接上的地方（不在对话页）点了没反应 */
internal val LocalWidgetSay = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * 一个活着的组件。滚出屏幕不销毁，留在 [lives] 里，滚回来还是原样（动画不重放、点过的还在）；
 * 留不下的（超过 [KEEP] 个）才销毁，再回来就重跑一遍。
 */
internal class Live(val view: WebView) {
    var onSize: (Float) -> Unit = {}
    var onFail: (String) -> Unit = {}
    var onSay: (String) -> Unit = {}

    /** 正画在屏幕上。同一段原文在对话里出现两次时，第二处不抢这一个 */
    var shown = false

    /** 手指最近一次碰它是什么时候。[Bridge.say] 只认刚碰过的 */
    @Volatile
    var touchedAt = 0L
}

/** 最多留这么多个活的 */
private const val KEEP = 4

private val lives = object : LruCache<String, Live>(KEEP) {
    override fun entryRemoved(evicted: Boolean, key: String, old: Live, new: Live?) {
        // 还画着的不动，等它离开屏幕时发现自己不在册上再销毁
        if (!old.shown) old.view.destroy()
    }
}

/** 画坏了的记着，滚回来直接是那一行，不再跑一遍 */
private val errors = LruCache<String, String>(24)

/** 点了之后这么久以内调的 say 才算数：脚本不能自己替他说话 */
internal const val SAY_WITHIN_MS = 1500L

@Composable
internal fun WidgetBlock(source: String, modifier: Modifier, fallback: @Composable () -> Unit) {
    val colors = DaodianColors.current
    val dark = colors == DarkPalette
    val cacheKey = "$dark\u0000$source"
    var height by remember(cacheKey) { mutableFloatStateOf(heights[cacheKey] ?: FIRST_H) }
    var error by remember(cacheKey) { mutableStateOf(errors[cacheKey]) }
    val broken by rememberUpdatedState(LocalDrawingBroken.current)
    val say by rememberUpdatedState(LocalWidgetSay.current)

    val failed = error
    if (failed != null) {
        // 只留一行，原文点开看 —— 一大段代码摊在对话里没人要看
        var open by remember(cacheKey) { mutableStateOf(false) }
        Column(modifier) {
            Text(
                if (open) "这张没画出来（$failed）· 收起" else "这张没画出来 · 看原文",
                Modifier.clickable { open = !open },
                style = DaodianType.caption,
                color = colors.hint
            )
            if (open) {
                Spacer(Modifier.height(8.dp))
                fallback()
            }
        }
        return
    }

    // 换了内容或主题就是另一个 WebView，不在旧的上面重载
    key(cacheKey) {
        val holder = remember { arrayOfNulls<Live>(1) }
        AndroidView(
            modifier = modifier.fillMaxWidth().height(height.dp),
            factory = { context ->
                val kept = lives[cacheKey]?.takeIf { !it.shown && it.view.context === context }
                val live = kept ?: Live(sandboxed(WebView(context))).also { fresh ->
                    fresh.view.addJavascriptInterface(Bridge(fresh), "__dd")
                    fresh.view.setOnTouchListener { _, _ ->
                        fresh.touchedAt = SystemClock.uptimeMillis()
                        false
                    }
                    fresh.view.loadDataWithBaseURL(null, page(source, colors), "text/html", "utf-8", null)
                    // 同一段原文已经有一个画在别处：这个不进册，离开屏幕就销毁
                    if (lives[cacheKey]?.shown != true) lives.put(cacheKey, fresh)
                }
                live.onSize = { h ->
                    val next = h.coerceIn(1f, MAX_H)
                    heights.put(cacheKey, next)
                    height = next
                }
                live.onFail = { m ->
                    if (error == null) {
                        val short = m.take(120)
                        errors.put(cacheKey, short)
                        error = short
                        broken?.invoke(short)
                    }
                }
                live.onSay = { text -> say?.invoke(text) }
                live.shown = true
                holder[0] = live
                if (kept != null) {
                    (live.view.parent as? ViewGroup)?.removeView(live.view)
                    live.view.onResume()
                }
                live.view
            },
            onRelease = { view ->
                val live = holder[0]
                live?.shown = false
                live?.onSize = {}
                live?.onFail = {}
                live?.onSay = {}
                // 还在册上的留着（先停掉，省电），不在册上的、画坏了的销毁
                if (live != null && lives[cacheKey] === live && errors[cacheKey] == null) {
                    view.onPause()
                } else {
                    if (live != null && lives[cacheKey] === live) lives.remove(cacheKey)
                    view.destroy()
                }
            }
        )
    }
}
