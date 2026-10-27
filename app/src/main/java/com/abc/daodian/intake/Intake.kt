package com.abc.daodian.intake

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.abc.daodian.intake.core.format.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通知监听层的门面：收到一条交给谁，以及跟「通知使用权」、监听连没连着打交道的几件事。见 DESIGN.md §2.3
 *
 * 收到的都交给 [NoticeHub]：硬条件 → 收件箱 → 通知管家（§12）。这一层只抓、只交，不读懂。
 * 系统服务本身是 [NoticeListenerService]，全 app 只有这一个 —— 通知使用权按组件授，多一个就得再去开一次。
 */
object Intake {

    /**
     * 收到一条（实时的、扫到的、重读的），过一遍流水线，都过完才返回。返回落没落进收件箱。
     * 标题都记一笔（[RecentTitles]，建规则时挑人用）
     *
     * @param lastLook 遮蔽版的最后一次重读，还是遮蔽版就不等了
     */
    internal suspend fun receive(context: Context, notice: Notice, lastLook: Boolean = false): Boolean {
        RecentTitles.note(notice.pkg, Formats.of(notice.pkg).normalize(notice).title)
        return NoticeHub.receive(context, notice, lastLook)
    }

    /** 刚存下一条遮蔽版：叫监听层过一会儿单读这一条（流水线的 `Wake` 叫的） */
    internal fun reread(notice: Notice) {
        live?.reread(notice)
    }

    // ---------------- 通知使用权 ----------------

    private fun component(context: Context) = ComponentName(context, NoticeListenerService::class.java)

    /**
     * 给没给**这个组件**通知使用权。按组件查，不按包名：改名、挪包后旧组件名的授权还挂在包名下，
     * 按包名查会说「开着」，其实系统根本不来绑新的这个
     */
    fun granted(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(context)) == true
    }.getOrDefault(false)

    /** 直达本 app 的「通知使用权」开关 */
    fun grantIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())

    /**
     * 系统遮蔽敏感通知时填进正文的那句话。直接问 framework 要（隐藏资源
     * `android:string/redacted_notification_message`），跟着系统语言走；问不到才退回中文原句。
     * 为什么会被遮蔽见 DESIGN.md §2.3
     */
    val REDACTED: String by lazy {
        runCatching {
            val res = Resources.getSystem()
            val id = res.getIdentifier("redacted_notification_message", "string", "android")
            id.takeIf { it != 0 }?.let { res.getString(it) }
        }.getOrNull() ?: "敏感数据已隐藏"
    }

    // ---------------- 监听连没连着、扫一遍、重连 ----------------

    /** [since]：连上 / 断开的那一刻；这个进程里还没连上过是 null */
    data class Listener(val connected: Boolean, val since: Long?)

    private val _listener = MutableStateFlow(Listener(connected = false, since = null))

    /**
     * 监听现在连没连着。只是这个进程里的状态：进程被杀、重新起来、系统还没绑回来之前是没连着。
     * 连着也不保证回调都来（荣耀冻住进程时会丢），所以才有扫一遍
     */
    val listener: StateFlow<Listener> = _listener.asStateFlow()

    @Volatile
    private var live: NoticeListenerService? = null

    internal fun attach(service: NoticeListenerService) {
        live = service
        _listener.value = Listener(connected = true, since = System.currentTimeMillis())
    }

    internal fun detach(service: NoticeListenerService) {
        if (live !== service) return
        live = null
        _listener.value = Listener(connected = false, since = System.currentTimeMillis())
    }

    /** 扫一遍的结果：通知栏里一共挂着 [active] 条，落进收件箱的 [stored] 条（新存的、补了规则的；早就收过的不算） */
    data class Sweep(val active: Int, val stored: Int)

    /**
     * 现在把通知栏扫一遍，都交完才返回。监听没连着返回 null。只扫得到还挂在通知栏里的 —— 划掉了的，系统也不留。
     * [how] 写进 [Notice.how]：organize（整理前）/ tap（页面上点的）……
     */
    suspend fun sweep(how: String): Sweep? = withContext(Dispatchers.IO) { live?.collect(how) }

    /** 通知栏里现在挂着的，只读不交（建规则时预演、挑发件人用）。监听没连着返回 null */
    suspend fun active(): List<Notice>? = withContext(Dispatchers.IO) { live?.snapshot("preview") }

    /** 荣耀杀掉监听后系统不一定自己绑回来；每次冷启动催一下 */
    fun rebind(context: Context) {
        if (granted(context)) runCatching { NotificationListenerService.requestRebind(component(context)) }
    }

    /**
     * 重连，等它连上（最多 [timeoutMillis]）。连着的先让它自己断开再请系统绑回来；没连着的直接请系统绑。
     *
     * 系统只肯重绑「之前自己请求断开过」的监听（`ManagedServices.setComponentState` 状态没变就直接返回）：
     * 进程被杀后系统没自动绑回来的，这里请了不管用，只能去系统设置把通知使用权关掉再打开。
     * **不用「禁用再启用组件」那一招**：Android 15 收到组件变化会清掉「查不到服务」的授权
     * （`trimApprovedListsForInvalidServices`，查的时候不带 MATCH_DISABLED_COMPONENTS），一禁用使用权就没了。
     */
    suspend fun reconnect(context: Context, timeoutMillis: Long = 6000): Boolean {
        if (!granted(context)) return false
        live?.let { service ->
            runCatching { service.requestUnbind() }
            withTimeoutOrNull(2000) { listener.first { !it.connected } }
        }
        runCatching { NotificationListenerService.requestRebind(component(context)) }
        return withTimeoutOrNull(timeoutMillis) { listener.first { it.connected } } != null
    }
}
