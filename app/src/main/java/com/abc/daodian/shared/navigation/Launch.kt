package com.abc.daodian.shared.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 从 app 外面（小组件、通知、桌面速记）拉起 app 并去到某一页。
 *
 * 只说路由字符串，不认识任何页面：路由表由各模块自己注册（`agent/shell/FeatureUi.routes`）。
 * 也不 import 宿主 Activity —— 按包名找本 app 的启动器入口，模块就不用依赖根目录。
 *
 * 三样可选：
 * - [route]：去哪一页，比如 `reminder/edit?id=7`。不给就是对话页
 * - [trigger]：到对话页开一轮 app 发起的，比如 `ledger:check`
 * - [say]：到对话页替你把这句话发出去（桌面速记放不下问卡时交过来的）
 */
object Launch {

    private const val EXTRA_ROUTE = "com.abc.daodian.launch.ROUTE"
    private const val EXTRA_TRIGGER = "com.abc.daodian.launch.TRIGGER"
    private const val EXTRA_SAY = "com.abc.daodian.launch.SAY"

    data class Request(val route: String?, val trigger: String?, val say: String?)

    fun intent(context: Context, route: String? = null, trigger: String? = null, say: String? = null): Intent =
        Intent()
            .setComponent(host(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                route?.let { putExtra(EXTRA_ROUTE, it) }
                trigger?.let { putExtra(EXTRA_TRIGGER, it) }
                say?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SAY, it) }
            }

    /** 宿主 Activity 用它把 intent 翻回去处。不是经这里来的（点 app 图标）返回 null */
    fun requestOf(intent: Intent?): Request? {
        intent ?: return null
        val route = intent.getStringExtra(EXTRA_ROUTE)
        val trigger = intent.getStringExtra(EXTRA_TRIGGER)
        val say = intent.getStringExtra(EXTRA_SAY)
        if (route == null && trigger == null && say == null) return null
        return Request(route, trigger, say)
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
