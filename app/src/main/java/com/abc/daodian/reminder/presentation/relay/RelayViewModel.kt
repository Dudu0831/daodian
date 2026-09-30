package com.abc.daodian.reminder.presentation.relay

import android.app.Application
import android.provider.Telephony
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.reminder.relay.Relay
import com.abc.daodian.reminder.relay.RelayDatabase
import com.abc.daodian.reminder.relay.RelaySettings
import com.abc.daodian.shared.apps.AppCatalog
import com.abc.daodian.shared.notify.NoticeHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 派活页（[RelayScreen]）。试验版：听谁、暗号、听哪个 app、她发来的每一句 */
class RelayViewModel(app: Application) : AndroidViewModel(app) {

    /** null = 还没读出来 */
    val settings: StateFlow<RelaySettings.Values?> =
        RelaySettings.flow(app).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages = RelayDatabase.get(app).dao().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seen = Relay.seen

    val connected = NoticeHub.connected

    private val _apps = MutableStateFlow<List<AppCatalog.App>?>(null)

    /** 装着的 app，聊天软件排前面。null = 还在读 */
    val apps: StateFlow<List<AppCatalog.App>?> = _apps.asStateFlow()

    /** 「扫一遍」之后那句话 */
    var sweepNote by mutableStateOf<String?>(null)
        private set

    private val sms: String? = runCatching { Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val all = AppCatalog.launchable(app)
            _apps.value = all.sortedBy { if (isChat(it.pkg)) 0 else 1 }
        }
    }

    fun isChat(pkg: String) = pkg in CHAT || pkg == sms

    /** 系统给没给通知使用权（按包名看，够试验用） */
    fun granted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(getApplication()).contains(getApplication<Application>().packageName)

    fun setApp(pkg: String, on: Boolean) = viewModelScope.launch {
        RelaySettings.setApp(getApplication(), pkg, on)
        // 刚勾上的，通知栏里挂着的也过一遍：「听谁」那一栏马上有名字可挑
        if (on) NoticeHub.sweepNow()
    }

    fun setWho(who: String) = viewModelScope.launch { RelaySettings.setWho(getApplication(), who) }

    fun setCode(code: String) = viewModelScope.launch { RelaySettings.setCode(getApplication(), code) }

    fun simulate(text: String) = viewModelScope.launch { Relay.simulate(getApplication(), text) }

    fun retry(id: Long) = Relay.retry(getApplication(), id)

    fun sweep() = viewModelScope.launch {
        sweepNote = "在扫……"
        sweepNote = when (val n = NoticeHub.sweepNow()) {
            null -> "监听没连着，扫不了"
            else -> "通知栏里挂着 $n 条，都过了一遍"
        }
    }

    fun clear() = viewModelScope.launch { RelayDatabase.get(getApplication()).dao().clear() }

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
