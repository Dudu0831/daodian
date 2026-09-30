package com.abc.daodian.intake.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.intake.Intake
import com.abc.daodian.intake.NoticeSubscriber
import com.abc.daodian.shared.apps.AppCatalog
import com.abc.daodian.shared.format.Format
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「扫一遍」「重连」的结果，[at] 是点的那一刻 */
sealed interface ListenerAction {
    data object Idle : ListenerAction
    data object Sweeping : ListenerAction
    data object Reconnecting : ListenerAction
    data class Swept(val at: Long, val sweep: Intake.Sweep, val reconnected: Boolean) : ListenerAction
    data class Reconnected(val at: Long) : ListenerAction
    /** 请了系统也没绑回来 */
    data class NotConnected(val at: Long) : ListenerAction
}

/** 一个订阅者听着哪些 app（名字按拼音排） */
data class Subscription(val subscriber: NoticeSubscriber, val apps: List<String>)

/** 按 app 看：这个 app 的通知交给谁 */
data class Route(val app: String, val to: List<String>)

/** 「通知监听」页和设置组（[IntakeStatusScreen]、[IntakeSettingsSection]）。按 Activity 取，两处同一份 */
class IntakeViewModel(app: Application) : AndroidViewModel(app) {

    val listener: StateFlow<Intake.Listener> = Intake.listener

    private val collator = Collator.getInstance(Locale.CHINA)

    /** 每个订阅者听着哪些 app，按注册顺序。null = 还没读出来 */
    val subscriptions: StateFlow<List<Subscription>?> = Intake.routesFlow(app)
        .map { routes ->
            Intake.subscribers.map { s ->
                Subscription(s, routes[s.id].orEmpty().map { AppCatalog.label(app, it) }.sortedWith(collator))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 按 app 看：只列有人听的，按名字排 */
    val routes: StateFlow<List<Route>> = Intake.routesFlow(app)
        .map { routes ->
            val byPkg = LinkedHashMap<String, MutableList<String>>()
            Intake.subscribers.forEach { s -> routes[s.id].orEmpty().forEach { byPkg.getOrPut(it) { mutableListOf() } += s.label } }
            byPkg.map { (pkg, to) -> Route(AppCatalog.label(app, pkg), to) }.sortedWith(compareBy(collator) { it.app })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _action = MutableStateFlow<ListenerAction>(ListenerAction.Idle)
    val action: StateFlow<ListenerAction> = _action.asStateFlow()

    private val busy get() = _action.value == ListenerAction.Sweeping || _action.value == ListenerAction.Reconnecting

    fun granted(): Boolean = Intake.granted(getApplication())

    fun grantIntent() = Intake.grantIntent(getApplication())

    /** 扫一遍：把通知栏里挂着的再交一次；监听没连着就先请系统绑回来，连上了再扫 */
    fun sweep() {
        if (busy) return
        _action.value = ListenerAction.Sweeping
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            var result = Intake.sweep(HOW)
            var reconnected = false
            if (result == null && Intake.reconnect(getApplication())) {
                reconnected = true
                result = Intake.sweep(HOW)
            }
            _action.value = result?.let { ListenerAction.Swept(start, it, reconnected) } ?: ListenerAction.NotConnected(start)
        }
    }

    /** 重连：连着的也断开重绑一次（荣耀冻过进程、回调不来时试试） */
    fun reconnect() {
        if (busy) return
        _action.value = ListenerAction.Reconnecting
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            _action.value = if (Intake.reconnect(getApplication())) ListenerAction.Reconnected(start) else ListenerAction.NotConnected(start)
        }
    }

    companion object {
        /** 页面上点的，写进 Notice.how */
        private const val HOW = "tap"

        /** 「连着 · 今天 09:12 起」「断了 · 9月29日 22:10」「这次打开 app 以来还没连上过」 */
        fun listenerNote(l: Intake.Listener): String = when {
            l.connected -> "连着" + (l.since?.let { " · ${since(it)} 起" } ?: "")
            l.since != null -> "断了 · ${since(l.since)}"
            else -> "这次打开 app 以来还没连上过"
        }

        private fun since(millis: Long): String {
            val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            return if (day == LocalDate.now()) "今天 ${Format.clock(millis)}" else Format.humanDateTimeShort(millis)
        }
    }
}
