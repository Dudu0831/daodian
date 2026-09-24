package com.abc.daodian.ledger.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 听哪几家的通知，以及跟「通知使用权」、监听连没连着打交道的几件事。
 *
 * 加一家：在 [ALL] 里加一行（包名 + 人话名字）就行 —— 采集、整理、界面都从这里取。
 * 各家通知给得出什么，见 DESIGN.md §10.2。
 */
object PaySources {

    data class Source(val pkg: String, val name: String)

    val ALL = listOf(
        Source("com.eg.android.AlipayGphone", "支付宝"),
        Source("cmb.pb", "招商银行"),
        Source("com.cmbchina.ccd.pluto.cmbActivity", "掌上生活"),
        Source("com.chinamworld.main", "建设银行"),
    )

    val PACKAGES: Set<String> = ALL.mapTo(HashSet()) { it.pkg }

    fun nameOf(pkg: String): String = ALL.firstOrNull { it.pkg == pkg }?.name ?: pkg

    /** 界面上写「支付宝、招行…」时用 */
    val names: String get() = ALL.joinToString("、") { it.name }

    /**
     * 系统遮蔽敏感通知时填进正文的那句话。直接问 framework 要（隐藏资源
     * `android:string/redacted_notification_message`），跟着系统语言走；问不到才退回中文原句。
     * 为什么会被遮蔽见 DESIGN.md §10.2。
     */
    val REDACTED: String by lazy {
        runCatching {
            val res = Resources.getSystem()
            val id = res.getIdentifier("redacted_notification_message", "string", "android")
            id.takeIf { it != 0 }?.let { res.getString(it) }
        }.getOrNull() ?: "敏感数据已隐藏"
    }

    /** app 内广播：让采集器把当前通知栏再扫一遍 */
    const val ACTION_SWEEP = "com.abc.daodian.PAY_SWEEP"

    private fun component(context: Context) = ComponentName(context, PaySampler::class.java)

    /**
     * 用户给没给**这个组件**通知使用权。按组件查，不按包名：挪包、改名后旧组件名的授权还挂在包名下，
     * 按包名查会说「开着」，其实系统根本不来绑新的这个。
     */
    fun granted(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(context)) == true
    }.getOrDefault(false)

    /** 直达本 app 的「通知使用权」开关 */
    fun grantIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())

    /** 让采集器把通知栏再扫一遍（遮蔽的正文过一会儿再读往往是全的）。整理之前先喊一声；监听没连着就没人听 */
    fun sweep(context: Context) {
        context.applicationContext.sendBroadcast(Intent(ACTION_SWEEP).setPackage(context.packageName))
    }

    /** 荣耀杀掉监听后系统不一定自己绑回来；app 每次冷启动催一下 */
    fun rebind(context: Context) {
        if (granted(context)) runCatching { NotificationListenerService.requestRebind(component(context)) }
    }

    // ---------------- 监听连没连着（抓取页看它） ----------------

    /** [since]：连上 / 断开的那一刻；这个进程里还没连上过是 null */
    data class Listener(val connected: Boolean, val since: Long?)

    private val _listener = MutableStateFlow(Listener(connected = false, since = null))

    /**
     * 监听现在连没连着。只是这个进程里的状态：进程被杀、重新起来、系统还没绑回来之前是没连着。
     * 连着也不保证回调都来（荣耀冻住进程时会丢，DESIGN.md §10.2），所以才有手动抓。
     */
    val listener: StateFlow<Listener> = _listener.asStateFlow()

    @Volatile
    private var live: PaySampler? = null

    internal fun attach(sampler: PaySampler) {
        live = sampler
        _listener.value = Listener(connected = true, since = System.currentTimeMillis())
    }

    internal fun detach(sampler: PaySampler) {
        if (live !== sampler) return
        live = null
        _listener.value = Listener(connected = false, since = System.currentTimeMillis())
    }

    /**
     * 现在就把通知栏里那几家的通知收一遍，存完才返回。返回通知栏里挂着几条；监听没连着返回 null。
     * 只收得到还挂在通知栏里的 —— 划掉了的，系统也不留。
     */
    suspend fun sweepNow(how: String): Int? = withContext(Dispatchers.IO) { live?.collect(how) }

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
        live?.let { sampler ->
            runCatching { sampler.requestUnbind() }
            withTimeoutOrNull(2000) { listener.first { !it.connected } }
        }
        runCatching { NotificationListenerService.requestRebind(component(context)) }
        return withTimeoutOrNull(timeoutMillis) { listener.first { it.connected } } != null
    }
}
