package com.abc.daodian.ledger.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.ledger.capture.PaySources
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.data.db.RawNotification
import com.abc.daodian.ledger.organize.OrganizeWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 抓到的一条原始通知，[txnId] 是整理后它进了哪一笔（没进账是 null） */
data class CapturedRow(val raw: RawNotification, val txnId: Long?)

/** 手动抓 / 重连这两个按钮的结果，[at] 是点的那一刻 */
sealed interface CaptureAction {
    data object Idle : CaptureAction
    data object Grabbing : CaptureAction
    data object Reconnecting : CaptureAction

    /** 通知栏里挂着 [seen] 条那几家的，这次新存进来 [saved] 条（连着的实时回调、连上时那一扫也算） */
    data class Grabbed(val at: Long, val seen: Int, val saved: Int, val reconnected: Boolean) : CaptureAction

    /** 监听没连上：请了系统也没绑回来 */
    data class NotConnected(val at: Long) : CaptureAction

    data class Reconnected(val at: Long) : CaptureAction
}

/**
 * 抓取页（[CaptureScreen]）：看抓到了什么、监听连没连着、手动抓一下。
 * 采集本身在 `capture/PaySampler`，这里只看、只喊它。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = LedgerStore.get(app).dao

    val listener: StateFlow<PaySources.Listener> = PaySources.listener

    val rows: StateFlow<List<CapturedRow>?> = dao.observeRecentRaws(LIMIT)
        .mapLatest { raws ->
            val owners = if (raws.isEmpty()) emptyMap() else dao.ownersOf(raws.map { it.id }).associate { it.rawId to it.txnId }
            raws.map { CapturedRow(it, owners[it.id]) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val total: StateFlow<Int> = dao.observeRawCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pending: StateFlow<Int> = dao.observePendingRaws().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastPosted: StateFlow<Long?> = dao.observeLastPosted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val organizing: StateFlow<Boolean> = AgentActivity.running.map { list -> list.any { it.id == "organize" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _action = MutableStateFlow<CaptureAction>(CaptureAction.Idle)
    val action: StateFlow<CaptureAction> = _action.asStateFlow()

    private val busy get() = _action.value == CaptureAction.Grabbing || _action.value == CaptureAction.Reconnecting

    /** 现在抓一下：扫通知栏；监听没连着就先请系统绑回来，连上了再扫 */
    fun grab() {
        if (busy) return
        _action.value = CaptureAction.Grabbing
        viewModelScope.launch {
            val app = getApplication<Application>()
            val start = System.currentTimeMillis()
            var seen = PaySources.sweepNow(HOW)
            var reconnected = false
            if (seen == null && PaySources.reconnect(app)) {
                reconnected = true
                seen = PaySources.sweepNow(HOW)
            }
            _action.value = if (seen == null) {
                CaptureAction.NotConnected(start)
            } else {
                CaptureAction.Grabbed(start, seen, dao.countCapturedSince(start), reconnected)
            }
        }
    }

    /** 重连：连着的也断开重绑一次（荣耀冻过进程、回调不来时试试） */
    fun reconnect() {
        if (busy) return
        _action.value = CaptureAction.Reconnecting
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            _action.value = if (PaySources.reconnect(getApplication())) CaptureAction.Reconnected(start)
            else CaptureAction.NotConnected(start)
        }
    }

    fun organizeNow() = OrganizeWorker.runNow(getApplication())

    companion object {
        /** 页上列最近这么多条 */
        const val LIMIT = 100

        /** 手动抓的写进 capturedHow 的值 */
        private const val HOW = "tap"
    }
}
