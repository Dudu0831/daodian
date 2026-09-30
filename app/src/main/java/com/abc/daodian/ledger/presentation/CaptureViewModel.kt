package com.abc.daodian.ledger.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.intake.Intake
import com.abc.daodian.ledger.capture.LedgerCapture
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

/** 「现在抓一下」的结果，[at] 是点的那一刻 */
sealed interface CaptureAction {
    data object Idle : CaptureAction
    data object Grabbing : CaptureAction

    /** 通知栏里挂着 [seen] 条勾上的 app 的，这次新存进来 [saved] 条（连着的实时回调、连上时那一扫也算） */
    data class Grabbed(val at: Long, val seen: Int, val saved: Int, val reconnected: Boolean) : CaptureAction

    /** 监听没连上：请了系统也没绑回来 */
    data class NotConnected(val at: Long) : CaptureAction
}

/**
 * 抓取页（[CaptureScreen]）：看抓到了什么、手动抓一下。
 * 抓通知的是通知监听层（[Intake]），存进库的是 `capture/LedgerCapture`；这里只看、只喊它扫。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = LedgerStore.get(app).dao

    val listener: StateFlow<Intake.Listener> = Intake.listener

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

    private val busy get() = _action.value == CaptureAction.Grabbing

    /** 现在抓一下：扫通知栏；监听没连着就先请系统绑回来，连上了再扫 */
    fun grab() {
        if (busy) return
        _action.value = CaptureAction.Grabbing
        viewModelScope.launch {
            val app = getApplication<Application>()
            val start = System.currentTimeMillis()
            var seen = Intake.sweep(HOW)?.mine()
            var reconnected = false
            if (seen == null && Intake.reconnect(app)) {
                reconnected = true
                seen = Intake.sweep(HOW)?.mine()
            }
            _action.value = if (seen == null) {
                CaptureAction.NotConnected(start)
            } else {
                CaptureAction.Grabbed(start, seen, dao.countCapturedSince(start), reconnected)
            }
        }
    }

    fun organizeNow() = OrganizeWorker.runNow(getApplication())

    /** 通知栏里挂着的、交给记账的几条 */
    private fun Intake.Sweep.mine(): Int = routed[LedgerCapture.id] ?: 0

    companion object {
        /** 页上列最近这么多条 */
        const val LIMIT = 100

        /** 手动抓的写进 capturedHow 的值 */
        private const val HOW = "tap"
    }
}
