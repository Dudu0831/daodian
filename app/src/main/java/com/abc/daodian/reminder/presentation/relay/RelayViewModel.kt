package com.abc.daodian.reminder.presentation.relay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.reminder.relay.Relay
import com.abc.daodian.reminder.relay.RelayDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 她发来的（[RelayScreen]）：派活试验版收到的每一句、试一句。
 * 听谁、暗号在提醒设置页改（ReminderViewModel）；通知使用权、听哪些 app 归通知监听层（DESIGN.md §2.3）。
 */
class RelayViewModel(app: Application) : AndroidViewModel(app) {

    val messages = RelayDatabase.get(app).dao().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun simulate(text: String) = viewModelScope.launch { Relay.simulate(getApplication(), text) }

    fun retry(id: Long) = Relay.retry(getApplication(), id)

    fun clear() = viewModelScope.launch { RelayDatabase.get(getApplication()).dao().clear() }
}
