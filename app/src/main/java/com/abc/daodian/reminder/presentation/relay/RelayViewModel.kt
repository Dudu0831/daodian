package com.abc.daodian.reminder.presentation.relay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.intake.Intake
import com.abc.daodian.reminder.relay.Relay
import com.abc.daodian.reminder.relay.RelayDatabase
import com.abc.daodian.reminder.relay.RelaySettings
import com.abc.daodian.shared.apps.AppCatalog
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 派活页（[RelayScreen]）。试验版：听谁、暗号、她发来的每一句。
 * 通知使用权、连没连着、听哪些 app 都归通知监听层，这里只读、只写一行，点了跳过去（DESIGN.md §2.3）。
 */
class RelayViewModel(app: Application) : AndroidViewModel(app) {

    /** null = 还没读出来 */
    val settings: StateFlow<RelaySettings.Values?> =
        RelaySettings.flow(app).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages = RelayDatabase.get(app).dao().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seen = Relay.seen

    val listener = Intake.listener

    /** 派活听着的 app 的名字。null = 还没读出来 */
    val apps: StateFlow<List<String>?> = Intake.appsFlow(app, Relay.id)
        .map { set -> set.map { AppCatalog.label(app, it) }.sortedWith(Collator.getInstance(Locale.CHINA)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun granted(): Boolean = Intake.granted(getApplication())

    fun setWho(who: String) = viewModelScope.launch { RelaySettings.setWho(getApplication(), who) }

    fun setCode(code: String) = viewModelScope.launch { RelaySettings.setCode(getApplication(), code) }

    fun simulate(text: String) = viewModelScope.launch { Relay.simulate(getApplication(), text) }

    fun retry(id: Long) = Relay.retry(getApplication(), id)

    fun clear() = viewModelScope.launch { RelayDatabase.get(getApplication()).dao().clear() }
}
