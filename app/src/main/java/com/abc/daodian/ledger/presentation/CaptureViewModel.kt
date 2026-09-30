package com.abc.daodian.ledger.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abc.daodian.agent.engine.background.AgentActivity
import com.abc.daodian.ledger.data.LedgerStore
import com.abc.daodian.ledger.data.db.RawNotification
import com.abc.daodian.ledger.organize.OrganizeWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** 抓到的一条原始通知，[txnId] 是整理后它进了哪一笔（没进账是 null） */
data class CapturedRow(val raw: RawNotification, val txnId: Long?)

/**
 * 抓取页（[CaptureScreen]）：看抓到了什么。
 * 抓通知的是通知监听层（`intake/Intake`），存进库的是 `capture/LedgerCapture`；这里只看。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = LedgerStore.get(app).dao

    val rows: StateFlow<List<CapturedRow>?> = dao.observeRecentRaws(LIMIT)
        .mapLatest { raws ->
            val owners = if (raws.isEmpty()) emptyMap() else dao.ownersOf(raws.map { it.id }).associate { it.rawId to it.txnId }
            raws.map { CapturedRow(it, owners[it.id]) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val total: StateFlow<Int> = dao.observeRawCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pending: StateFlow<Int> = dao.observePendingRaws().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val organizing: StateFlow<Boolean> = AgentActivity.running.map { list -> list.any { it.id == "organize" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun organizeNow() = OrganizeWorker.runNow(getApplication())

    companion object {
        /** 页上列最近这么多条 */
        const val LIMIT = 100
    }
}
