package com.abc.daodian.shared.notify

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统通知的分发台。app 里只有一个通知监听（记账那个，`ledger/capture/PaySampler`）——
 * 通知使用权按组件授，多一个就得让你再去系统设置里开一次。它收到任何一条（实时来的、扫通知栏扫到的）
 * 都在这里喊一声，别的模块要什么自己来接（[register]），不用 import 记账。
 *
 * 眼下只有派活在接（`reminder/relay`，试验版）。
 */
object NoticeHub {

    data class Notice(
        val pkg: String,
        val key: String,
        /** 发通知的 app 自己写的时刻（`Notification.when`），聊天软件是那条消息的时刻；没写就是系统收到的时刻 */
        val at: Long,
        val title: String?,
        val text: String?,
        /** 怎么抓到的：posted / active / unlock / … 同记账的原始通知 */
        val how: String
    )

    fun interface Sink {
        suspend fun take(context: Context, notice: Notice)
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    /** 冷启动时各模块挂上来（接头的 onAppStart）。同一个挂两次只算一次 */
    fun register(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    /** 监听收到一条。谁出错都不连累别人，也不连累记账 */
    suspend fun post(context: Context, notice: Notice) {
        val app = context.applicationContext
        sinks.forEach { sink ->
            try {
                sink.take(app, notice)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // 接的一方自己记错；这里只保证别的照收
            }
        }
    }

    // ---------------- 监听连没连着、手动扫一遍 ----------------

    private val _connected = MutableStateFlow(false)

    /** 只是这个进程里的状态，同记账抓取页（DESIGN.md §10.2） */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var owner: Any? = null

    @Volatile
    private var sweeper: (suspend () -> Int?)? = null

    fun attach(owner: Any, sweep: suspend () -> Int?) {
        this.owner = owner
        sweeper = sweep
        _connected.value = true
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        sweeper = null
        _connected.value = false
    }

    /** 现在把通知栏扫一遍，扫到的照样经 [post] 分下去。返回挂着几条；监听没连着是 null */
    suspend fun sweepNow(): Int? = sweeper?.invoke()
}
