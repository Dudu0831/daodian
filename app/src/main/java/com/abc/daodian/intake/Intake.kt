package com.abc.daodian.intake

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.abc.daodian.intake.data.IntakeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通知监听层的门面：谁在订阅、每个订阅者听哪些 app、收到一条交给谁，以及跟「通知使用权」、
 * 监听连没连着打交道的几件事。见 DESIGN.md §2.3
 *
 * 只抓、只分，**一条都不存、不读懂**：存原文、去重、叫模型都是订阅者的事。
 * 系统服务本身是 [NoticeListenerService]，全 app 只有这一个 —— 通知使用权按组件授，多一个就得再去开一次。
 */
object Intake {

    // ---------------- 订阅者 ----------------

    @Volatile
    var subscribers: List<NoticeSubscriber> = emptyList()
        private set

    @Volatile
    private var router = Router(emptyList())

    /** `DaodianApp.onCreate` 把根目录 `Features.kt` 的 `SUBSCRIBERS` 装进来。系统拉起监听之前一定已经装好 */
    fun install(list: List<NoticeSubscriber>) {
        router = Router(list)
        subscribers = list
    }

    fun subscriber(id: String): NoticeSubscriber? = subscribers.firstOrNull { it.id == id }

    // ---------------- 路由表：每个订阅者听哪些 app ----------------

    fun routesFlow(context: Context): Flow<Map<String, Set<String>>> = IntakeSettings.routesFlow(context)

    fun appsFlow(context: Context, id: String): Flow<Set<String>> = IntakeSettings.appsFlow(context, id)

    /** 勾上的那一刻，通知栏里已经挂着的也交一份（和监听刚连上时一样） */
    suspend fun setApp(context: Context, id: String, pkg: String, on: Boolean) {
        IntakeSettings.setApp(context, id, pkg, on)
        if (on) sweep(HOW_PICKED)
    }

    /** 收到一条：查路由表，交给勾了这个 app 的订阅者，都交完才返回。返回交给了谁（没人勾就是空的，这条扔掉） */
    internal suspend fun dispatch(context: Context, notice: Notice): List<NoticeSubscriber> {
        val r = router
        val targets = r.targets(notice.pkg, IntakeSettings.routes(context))
        if (targets.isEmpty()) return emptyList()
        val app = context.applicationContext
        return r.deliver(targets) { it.accept(app, notice) }
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

    /**
     * 扫一遍的结果：通知栏里一共挂着 [active] 条，交给各订阅者的各几条（[routed]，没勾的 app 不算）。
     * 交出去的不一定是新的 —— 订阅者早就收过的，它自己去重
     */
    data class Sweep(val active: Int, val routed: Map<String, Int>)

    /**
     * 现在把通知栏扫一遍，都交完才返回。监听没连着返回 null。只扫得到还挂在通知栏里的 —— 划掉了的，系统也不留。
     * [how] 写进 [Notice.how]：organize（整理前）/ tap（页面上点的）……
     */
    suspend fun sweep(how: String): Sweep? = withContext(Dispatchers.IO) { live?.collect(how) }

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

    /** 勾上 app 那一刻扫到的，写进 [Notice.how] */
    const val HOW_PICKED = "picked"
}
