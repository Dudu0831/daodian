package com.abc.daodian.assistant.conversation.render

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.abc.daodian.shared.theme.DaodianPalette
import com.abc.daodian.shared.theme.DarkPalette
import java.io.ByteArrayInputStream

/** 模型的脚本只够得着这三个口。都是从 WebView 自己的线程调进来的，先回主线程 */
internal class Bridge(private val live: Live) {
    @JavascriptInterface
    fun size(h: Float) {
        live.view.post { live.onSize(h) }
    }

    @JavascriptInterface
    fun fail(message: String) {
        live.view.post { live.onFail(message) }
    }

    /** 替他在对话里说一句。只认手指刚点过之后的，一句最多 200 字 */
    @JavascriptInterface
    fun say(text: String) {
        if (SystemClock.uptimeMillis() - live.touchedAt > SAY_WITHIN_MS) return
        // 一次点只说一句
        live.touchedAt = 0L
        val line = text.trim().take(200)
        if (line.isNotEmpty()) live.view.post { live.onSay(line) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun sandboxed(view: WebView): WebView = view.apply {
    setBackgroundColor(AndroidColor.TRANSPARENT)
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = WebView.OVER_SCROLL_NEVER
    settings.apply {
        javaScriptEnabled = true
        blockNetworkLoads = true
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        databaseEnabled = false
        setGeolocationEnabled(false)
        setSupportZoom(false)
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
    }
    webViewClient = object : WebViewClient() {
        // 什么都不取：就算 CSP、blockNetworkLoads 漏了，这里也给一个空的
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        // 不让跳走
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
    }
}

/** 外面包一层：CSP、色板变量、报错、量高度 */
internal fun page(raw: String, c: DaodianPalette): String {
    val source = if (c == DarkPalette) SvgColors.themed(raw, c) else raw
    fun v(color: androidx.compose.ui.graphics.Color) = SvgColors.hex(color)
    return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:">
<style>
:root{--ink:${v(c.ink)};--ink2:${v(c.ink2)};--muted:${v(c.muted)};--hint:${v(c.hint)};--rule:${v(c.rule)};--rule2:${v(c.rule2)};
--paper:${v(c.paper)};--surface:${v(c.surface)};--accent:${v(c.accent)};
--c1:${v(c.accent)};--c2:${v(c.ink2)};--c3:${v(c.muted)};--c4:${v(c.rule2)};--c5:${v(c.amber)}}
html,body{margin:0;padding:0;background:transparent;color:var(--ink);font:14px/1.5 sans-serif;overflow:hidden;
-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none}
#__root{overflow:hidden}
svg{max-width:100%;height:auto;display:block;fill:currentColor}
#__root>svg{width:100%}
button{font:inherit;color:inherit}
</style>
<script>var __t0=Date.now();
// 只认刚加载那两秒里的错：之后点出来的错不把整个组件换掉
window.onerror=function(m,s,l){if(Date.now()-__t0<2000)try{__dd.fail(String(m)+(l?' @'+l:''))}catch(e){}};
function say(t){try{__dd.say(String(t))}catch(e){}}</script>
</head><body><div id="__root">
$source
</div>
<script>(function(){var r=document.getElementById('__root');function s(){__dd.size(r.getBoundingClientRect().height)}
new ResizeObserver(s).observe(r);s();
setTimeout(function(){if(r.getBoundingClientRect().height<8)__dd.fail('画出来是空的')},800)})();</script>
</body></html>
""".trimIndent()
}
