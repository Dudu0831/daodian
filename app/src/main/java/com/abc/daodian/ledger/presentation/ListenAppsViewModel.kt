package com.abc.daodian.ledger.presentation

import android.app.Application
import android.provider.Telephony
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.shared.apps.AppCatalog
import com.abc.daodian.ledger.capture.PaySources
import com.abc.daodian.ledger.data.LedgerSettings
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「听哪些 app」这一页要画的。[top] 是进这一页那一刻在听的，[rest] 是其他装着的：
 * 分两段按进来那一刻分，这一页里勾勾取取不挪位置，下次进来才排到上面。
 * [readable] 为 false：系统不让看装了哪些 app，只列得出已经勾上的。
 */
data class ListenApps(
    val top: List<AppCatalog.App>,
    val rest: List<AppCatalog.App>,
    val gone: Set<String>,
    val readable: Boolean,
    /** 搜的时候不分段，按名字排成一张 */
    val all: List<AppCatalog.App>
)

/** 「听哪些 app」页（[ListenAppsScreen]）。每次进这一页新建一份（按导航栈那一页取），好让分段按进来那一刻重排 */
class ListenAppsViewModel(app: Application) : AndroidViewModel(app) {

    /** null = 还在读手机上装了哪些 app */
    private val _state = MutableStateFlow<ListenApps?>(null)
    val state: StateFlow<ListenApps?> = _state.asStateFlow()

    val listened: StateFlow<Set<String>> =
        LedgerSettings.listenFlow(app).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    /** 默认短信 app：勾上它，验证码也会存下来 */
    private val sms: String? = runCatching { Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull()

    init {
        load()
    }

    /** 从系统设置里允许了读应用列表回来，再读一遍 */
    fun reloadIfUnreadable() {
        if (_state.value?.readable == false) load()
    }

    private fun load() = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val pinned = LedgerSettings.listen(app)
        val installed = AppCatalog.launchable(app)
        val byPkg = installed.associateBy { it.pkg }
        val byName = compareBy(Collator.getInstance(Locale.CHINA)) { a: AppCatalog.App -> a.label }
        val top = pinned.map { byPkg[it] ?: AppCatalog.App(it, AppCatalog.label(app, it)) }.sortedWith(byName)
        val rest = installed.filter { it.pkg !in pinned }
        val state = ListenApps(
            top = top,
            rest = rest,
            gone = pinned.filterTo(HashSet()) { !AppCatalog.installed(app, it) },
            readable = installed.isNotEmpty(),
            all = (top + rest).sortedWith(byName)
        )
        _state.value = state

        // 图标一批一批画，先出来的先显示
        val px = (32 * app.resources.displayMetrics.density).roundToInt()
        val out = HashMap(_icons.value)
        (state.top + state.rest).filter { it.pkg !in out }.chunked(12).forEach { chunk ->
            chunk.forEach { a -> AppCatalog.icon(app, a.pkg, px)?.let { out[a.pkg] = it.asImageBitmap() } }
            _icons.value = HashMap(out)
        }
    }

    fun toggle(pkg: String, on: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        LedgerSettings.setListen(app, pkg, on)
        // 刚勾上的，通知栏里已经挂着的也收一份（和监听刚连上时一样）
        if (on) PaySources.sweep(app)
    }

    /** 勾上会把别的也一起存下来、发给模型的：聊天软件（付款通知和聊天是同一个 app）、默认短信（验证码）。只提醒，照样能勾 */
    fun alsoCaptures(pkg: String): String? = when {
        pkg in CHAT -> "聊天消息也会原样存下来、发给模型"
        pkg == sms -> "验证码、别的短信也会原样存下来、发给模型"
        else -> null
    }

    private companion object {
        val CHAT = setOf(
            "com.tencent.mm",            // 微信
            "com.tencent.mobileqq",      // QQ
            "com.tencent.tim",           // TIM
            "com.alibaba.android.rimet", // 钉钉
            "com.ss.android.lark"        // 飞书
        )
    }
}
