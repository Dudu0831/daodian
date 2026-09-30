package com.abc.daodian.intake.presentation

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.abc.daodian.intake.Intake
import com.abc.daodian.intake.NoticeSubscriber
import com.abc.daodian.shared.apps.AppCatalog
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 勾选页要画的。[top] 是进这一页那一刻在听的，[rest] 是其他装着的（订阅者说要排前面的在前）：
 * 分两段按进来那一刻分，这一页里勾勾取取不挪位置，下次进来才排到上面。
 * [readable] 为 false：系统不让看装了哪些 app，只列得出已经勾上的。
 */
data class AppChoices(
    val top: List<AppCatalog.App>,
    val rest: List<AppCatalog.App>,
    val gone: Set<String>,
    val readable: Boolean,
    /** 搜的时候不分段，按名字排成一张 */
    val all: List<AppCatalog.App>
)

/**
 * 某个订阅者听哪些 app（[AppPickerScreen]）。订阅者 id 从路由 `intake/apps/{id}` 来。
 * 每次进这一页新建一份（按导航栈那一页取），好让分段按进来那一刻重排
 */
class AppPickerViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {

    private val id: String = handle.get<String>("id").orEmpty()

    /** null = 路由里的 id 没有对应的订阅者（改过名、删掉的模块） */
    val subscriber: NoticeSubscriber? = Intake.subscriber(id)

    /** null = 还在读手机上装了哪些 app */
    private val _state = MutableStateFlow<AppChoices?>(null)
    val state: StateFlow<AppChoices?> = _state.asStateFlow()

    val listened: StateFlow<Set<String>> =
        (if (subscriber != null) Intake.appsFlow(app, id) else flowOf(emptySet()))
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    init {
        if (subscriber != null) load(subscriber)
    }

    /** 从系统设置里允许了读应用列表回来，再读一遍 */
    fun reloadIfUnreadable() {
        if (_state.value?.readable == false) subscriber?.let(::load)
    }

    private fun load(s: NoticeSubscriber) = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val pinned = Intake.appsFlow(app, s.id).first()
        val installed = AppCatalog.launchable(app)
        val byPkg = installed.associateBy { it.pkg }
        val byName = compareBy(Collator.getInstance(Locale.CHINA)) { a: AppCatalog.App -> a.label }
        val top = pinned.map { byPkg[it] ?: AppCatalog.App(it, AppCatalog.label(app, it)) }.sortedWith(byName)
        // launchable 已经按名字排好了；订阅者说要排前面的挪到最前，其余原样
        val rest = installed.filter { it.pkg !in pinned }.sortedBy { if (s.suggested(app, it.pkg)) 0 else 1 }
        val state = AppChoices(
            top = top,
            rest = rest,
            gone = pinned.filterTo(HashSet()) { !AppCatalog.installed(app, it) },
            readable = installed.isNotEmpty(),
            all = (top + installed.filter { it.pkg !in pinned }).sortedWith(byName)
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

    /** 勾上的那一刻，通知栏里已经挂着的也交一份（[Intake.setApp]） */
    fun toggle(pkg: String, on: Boolean) = viewModelScope.launch {
        Intake.setApp(getApplication(), id, pkg, on)
    }

    /** 勾上之后行底下那句红字（记账：聊天软件会连聊天一起存）。只提醒，照样能勾 */
    fun warn(pkg: String): String? = subscriber?.warn(getApplication(), pkg)
}
