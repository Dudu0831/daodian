package com.abc.daodian.shared.navigation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 从 app 外面（小组件、通知、桌面速记）拉起 app 并去到某一页。
 *
 * 只说路由字符串，不认识任何页面：路由表由各模块自己注册（`shell/entry/ShellEntry.routes`）。
 * 也不 import 宿主 Activity —— 按包名找本 app 的启动器入口，模块就不用依赖根目录。
 *
 * 四样可选：
 * - [route]：去哪一页，比如 `reminder/edit?id=7`。不给就是对话页
 * - [trigger]：到对话页开一轮 app 发起的，比如 `ledger:check`
 * - [say]：到对话页替你把这句话发出去（桌面速记放不下问卡时交过来的）
 * - [camera]：到对话页直接开相机，拍回来的排进待发那一行（长按图标的「拍一张」）
 */
object Launch {

    /**
     * 对话页（助手）的路由。存在通知栏里挂着的、小组件上的 PendingIntent 里，**不改这个字**：
     * 换了，升级前挂着的那些点进来 NavHost 找不到页会崩
     */
    const val HOME = "chat"

    @Volatile
    private var quickAddActivity: Class<out Activity>? = null

    /** 桌面速记那个 Activity。根目录启动时装进来 —— 这里不 import 它，小组件（提醒模块）也就不用 import 助手 */
    fun install(quickAdd: Class<out Activity>) {
        quickAddActivity = quickAdd
    }

    /** 拉起桌面速记那张纸（小组件右下角的墨印） */
    fun quickAdd(context: Context): Intent =
        Intent(context, checkNotNull(quickAddActivity) { "Launch 还没装（DaodianApp.onCreate）" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val EXTRA_ROUTE = "com.abc.daodian.launch.ROUTE"
    private const val EXTRA_TRIGGER = "com.abc.daodian.launch.TRIGGER"
    private const val EXTRA_SAY = "com.abc.daodian.launch.SAY"
    private const val EXTRA_CAMERA = "com.abc.daodian.launch.CAMERA"

    data class Request(val route: String?, val trigger: String?, val say: String?, val camera: Boolean = false)

    fun intent(
        context: Context,
        route: String? = null,
        trigger: String? = null,
        say: String? = null,
        camera: Boolean = false
    ): Intent =
        Intent()
            .setComponent(host(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                route?.let { putExtra(EXTRA_ROUTE, it) }
                trigger?.let { putExtra(EXTRA_TRIGGER, it) }
                say?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SAY, it) }
                if (camera) putExtra(EXTRA_CAMERA, true)
            }

    /** 宿主 Activity 用它把 intent 翻回去处。不是经这里来的（点 app 图标）返回 null */
    fun requestOf(intent: Intent?): Request? {
        intent ?: return null
        val route = intent.getStringExtra(EXTRA_ROUTE)
        val trigger = intent.getStringExtra(EXTRA_TRIGGER)
        val say = intent.getStringExtra(EXTRA_SAY)
        val camera = intent.getBooleanExtra(EXTRA_CAMERA, false)
        if (route == null && trigger == null && say == null && !camera) return null
        return Request(route, trigger, say, camera)
    }

    /**
     * 启动器入口那个 Activity。不用 getLaunchIntentForPackage 返回的 intent 本身：它带着 MAIN/LAUNCHER，
     * app 已经开着时系统只把任务切到前台、不把 intent 交给 onNewIntent，去处就丢了。
     */
    private fun host(context: Context): ComponentName =
        requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component) {
            "找不到启动器入口"
        }
}
